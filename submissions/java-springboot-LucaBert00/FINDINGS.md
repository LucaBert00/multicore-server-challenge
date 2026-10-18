# Findings — where the time actually goes

Notes from reading `bench/` and `WORKLOAD.md` in the challenge repo, before optimising.
No numbers here are measured on a box; they are what the load generator's own code says it
will send. The point is to aim the work, not to claim a result.

## Status

Conformance suite passes 197/197 (CI run `38938085881`, Ubuntu 24.04 + Postgres 18).
Item 8 changed no SQL: the per-comment `following` probe was measured, found not to be
per-row, and left as written. Nothing about performance has been measured yet.

## Request mix

From `WORKLOAD.md` (`%` of page views) and `bench/cmd/loadgen/vu.go` (calls per page view),
~1.8 calls per page view. Normalised to ~175 call-units:

| Endpoint | % of requests | Round trips |
|---|---|---|
| `GET /api/articles` (+ `tag`/`author`/`favorited`) | ~32% | **2** |
| `GET /api/tags` | ~21% | 1 |
| `GET /api/articles/:slug` | ~20% | 1 |
| `GET /api/articles/:slug/comments` | ~20% | **2** |
| profiles, writes, rest | ~7% | 1–2 |

So the two multi-round-trip endpoints together carry **~52% of all requests**.

## What the workload is not

**Deep offsets are a non-issue.** `vu.go:281`:

```go
func (v *VU) offset() string {
    if v.r.Float64() < 0.7 { return "0" }
    return strconv.Itoa(10 * (1 + v.r.IntN(4)))  // pages 2–5
}
```

Offsets are only `0, 10, 20, 30, 40`. With `limit=10`, the page query walks at most 50 index
entries. Keyset pagination would be pure overhead. (Assumed a risk before reading this.)

**`GET /api/tags` is cheap.** It is 21% of requests and unbounded, which looks alarming, but
`seed/make-seed.sh:25` seeds **200 tags** — a 200-row sort over ~2 KB. Not worth optimising.

## The actual finding

For every list request the pair is:

- page — `limit=10`, offset ≤ 40 → walks **≤50** index entries, evaluates the per-row
  subqueries (tags array, `favorites_count`, `favorited`, `following`)
- count — `SELECT count(*) FROM (SELECT 1 ... LIMIT 1000)` → walks **1000** index entries, no projection

**The count query touches roughly 20x more index tuples than the page it is paired with.**
Merging them into one round trip is the single largest available database win.

The trap: window functions evaluate *before* `LIMIT`/`OFFSET`, so `count(*) OVER ()` would
replace a 1000-entry scan with a full count over the whole table. The cap has to be preserved
explicitly, and the count still has to come back when the page is empty.

## Plan, in order

1. **~~Merge the page + count into one statement~~ — done.** (~32% of requests.) Preserved the
   `LIMIT 1000` cap; empty pages fall back to the standalone count. Conformance green.
2. **~~Single round trip for comments~~ — done.** (~20% of requests.) `LEFT JOIN LATERAL` off
   `articles` keeps 404 (no row) distinct from empty list (null comment columns). Conformance green.
3. **Right-size the pool.** `start.sh` used `10 x cores` = 80 backends against `max_connections=100`,
   on a box sharing 8 cores with Postgres. The pool is only a ceiling, and sub-millisecond queries at
   ~5.5k req/s need far less concurrency than 80 — 80 resident backends mostly buy scheduler thrash and
   idle memory. Now `3 x cores`. Also `connection-timeout` 5000ms → 1000ms, so saturation fails fast
   into the error budget instead of stalling past the `p99 < 1 s` gate.
4. **Compression: deliberately NOT enabled.** See below.
5. **Collapse the double `favorites` probe into one `LEFT JOIN LATERAL`.** Every article row ran
   `(SELECT count(*) ...)` plus `EXISTS (... AND user_id = :viewer)` over the same rows. Now one
   pass returns both (`count(*)` + `bool_or(user_id = :viewer)`). `bool_or` yields NULL when there
   are no favorites; `rs.getBoolean()` maps NULL → false so `favorited` is unchanged. Affects
   `ARTICLE_COLS` (lists, feed) and `ARTICLE_FULL_SELECT` (detail, favorite/unfavorite read-back) —
   ~52% of requests.
6. **Feed candidate list: one global page instead of a per-followee fan-out.** `LIST_FEED_COUNTED`
   built its candidate set with `CROSS JOIN LATERAL` — `articles_author_idx (author_id, id DESC)`
   walked once per followee, `LIMIT (limit + offset)` rows each — then top-N-sorted the union and
   paged it. Now `WHERE a.author_id IN (SELECT fo.followee_id FROM follows fo WHERE
   fo.follower_id = :viewer)` with a single `ORDER BY a.id DESC LIMIT/OFFSET`, the same shape as
   `LIST_AUTHOR_COUNTED`, and the same predicate `COUNT_FEED` already used.
   This is an equivalence, not an approximation: the global top-`limit + offset` of the union is a
   subset of the union of each author's top-`limit + offset` (anything in the global prefix is in its
   own author's prefix), and each author's prefix is a subset of the union — so paging either one
   yields the same rows. Ordering, pagination, capped count and the single-round-trip contract are
   unchanged; empty pages still fall back to `COUNT_FEED`. Cost went from one index walk + partial
   sort per followee to one semi-join against the same index. Part of the ~32% lists path.
7. **Page before probing: the offset rows were paying for probes they were thrown away from.** The
   per-row probes were not the problem on the list queries — the *paging* was. A top-level
   `ORDER BY a.id DESC LIMIT :limit OFFSET :offset` is applied after them, so the planner walks
   `offset + limit` entries of `articles_pkey` and runs the tag array, the favorites aggregate and the
   users join on every one of them, then discards the first `offset` rows: at `limit=10 offset=40` all
   three probes report `loops=50`. The page is now taken by a derived table first, so each probe runs
   exactly `limit` times. Root-node buffer counts and probe loop counts, median of 8 interleaved runs:

   | page (`limit=10`) | before: buffers / probe runs | after: buffers / probe runs |
   |---|---|---|
   | `offset=0` | 190 / 10 | 190 / 10 (unchanged, as expected) |
   | `offset=20` | 473 / 30 | 191 / 10 |
   | `offset=40` | 760 / 50 | 195 / 10 |

   Same statement, same single round trip, same capped count, same rows: verified row for row against
   the previous SQL for nine request shapes (all at offsets 0/20/40, default limit 20, author, tag,
   favorited, feed at offsets 0 and 40) — 90 rows, identical slug, tag list, favorites count,
   `favorited`, `following` and author. It applies to `LIST_ALL_COUNTED`, `LIST_AUTHOR_COUNTED` and
   `LIST_FEED_COUNTED`. `LIST_TAG_COUNTED` and `LIST_FAVORITED_COUNTED` choose their page with an
   `IN`-subquery on the article id, which already carries at most `limit` rows into the probes, so
   there is no offset waste to remove and they keep their shape. The capped total is untouched: still
   one `Seq Scan` of `articles` that stops at 1000 rows (~36 buffers, no per-row cost).
   `vu.go:281` only ever sends offsets 0–40, so 30% of list and feed requests were paying for up to 40
   rows of discarded probe work; the other 70% now provably pay exactly what they paid before.
8. **Comments hot path: the per-comment `following` probe — audited, left exactly as written.**
   `COMMENTS_PAGE` already fetches article + newest 100 comments in one round trip, so the only thing
   left on this path was the `EXISTS (... follows ...)` evaluated against `c.author_id` on every
   comment row. Measured rather than assumed: it is **not** per-row. `follower_id` is constant, so
   the planner pulls the `EXISTS` up as a single `SubPlan` with `loops=1` — one `Index Only Scan` on
   `follows_pkey` for the viewer's followees, then a hash membership test per comment row that has no
   node of its own. Median of 5 warm runs; root buffer counts were identical on all 5 of every row:

   | `COMMENTS_PAGE` variant | root buffers | probe node | probe `loops` | probe buffers |
   |---|---|---|---|---|
   | as written | 314 | `Index Only Scan` on `follows_pkey` | **1** | 4 |
   | probe rewritten as an explicit `IN`-subquery | 314 | same | 1 | 4 |
   | probe correlated on the inner var (control) | 310 | same | **100** | 300 |
   | as written, heaviest viewer in the dataset (892 followees) | 318 | same | 1 | 8 |
   | probe deleted | 310 | none | — | — |
   | probe deleted, heaviest viewer | 310 | none | — | — |

   Three things fall out of that table. The probe costs **4 buffers** (8 for a viewer following 892
   people) on a page of 100 comments, and that cost does not grow with the comment count: it is one
   index scan for the whole page, not one per row. The control row is what this probe *would* cost
   if it were per-row — 100 loops, 300 buffers — so that gap is the prize, and the planner already
   takes it. And the `IN`-subquery rewrite is the same plan to the buffer, so it removes nothing and
   was not adopted: the same reason as item 7, now confirmed on the comments path. The generic plan
   (`PREPARE` + `force_generic_plan`, i.e. the bound `:viewer`/`:slug` the app really sends through
   JDBC) is identical — `Index Cond: (follower_id = $1)`, `loops=1`.
   The `LEFT JOIN LATERAL` is one `Index Scan using comments_article_idx` with `Index Searches: 1`
   returning 100 rows in 6 buffers: one scan for the page whatever the comment count, which is why
   the round trip is worth keeping as one statement.
   Both failure shapes were re-checked on the measured statements rather than assumed: an article
   with zero comments returns one row with the comment columns null and `following` false (non-null,
   not true — `c.author_id` is null there, so the `EXISTS` matches nothing), and an unknown slug
   returns no row at all with every node `never executed`. Verdict: **verify-and-leave.** No probe
   and no round trip is removed by any rewrite available here, so the SQL is unchanged.

## Unmeasured

No application-level measurement exists in this project yet, so none of the above is a claimed
speedup: items 1–3 and 5–6 are round-trip / per-row-probe / query-plan removals reasoned about from the
request mix and the schema's indexes, not timed on a box. Conformance proves correctness only.

Item 7 is the one place with numbers behind it, and they are **statement-level** numbers from
`EXPLAIN (ANALYZE, BUFFERS)` on a throwaway Postgres 18 cluster seeded to the challenge's cardinalities
(100k users, 500k articles, 200 tags, 1.24M article_tags, 5.0M favorites, 2.5M comments, 2.0M follows —
row counts and per-row fan-outs from `bench/seed/seed.go`, values generated in SQL rather than by
`seedgen`). That is a laptop, warm cache, one page at a time: it is evidence about plans and buffer
traffic, not about this app's throughput on the challenge box, and no such claim is made from it.
Buffer counts and node loop counts are deterministic and are what the verdict rests on; the
wall-clock millisecond figures moved by 3–4x run to run on the same statement and are only quoted as
orders of magnitude.

Item 8 is the same kind of evidence, and it is worth being precise about what it does and does not
say. It says the probe is not per-row and costs 4 buffers; it does **not** say the app got faster,
because nothing changed — an audit that finds nothing to remove is a verdict, not a speedup. The
statement is still dominated by the `users` profile join (`loops=100`), and confirming that a probe
is cheap is not a measurement of the endpoint. The seed it was measured on came out at 100k users,
500k articles, 200 tags, 1,250,000 article_tags, 4,991,563 favorites, 2,504,281 comments and
1,999,046 follows, with follows per user averaging 20.0 (p50 12, p99 125, max 892) and 46,725
articles carrying no comments at all; `max_parallel_workers_per_gather = 0` and `jit = off` were
pinned so the plan is deterministic.

Three audit findings that the measurements contradicted, kept here because they were not obvious:

- **The `following` probe was never per-row.** With `follower_id` constant, the planner pulls the
  `EXISTS` up as an InitPlan: one `Index Only Scan` on `follows_pkey` for the viewer's followees
  (`loops=1`, ~23 rows, 4 buffers, tens of microseconds for the whole page) and then a hash membership
  test per row that has no node of its own. Writing it explicitly as
  `a.author_id IN (SELECT fo.followee_id FROM follows fo WHERE fo.follower_id = :viewer)` produced an
  identical plan and no measurable change, so it was not adopted. Item 8 re-measured this on the
  comments path rather than carrying the list-path result over, and got the same answer: `loops=1`,
  4 buffers, and an `IN`-subquery rewrite that is the same plan to the buffer.
- **The `tag ARRAY` inner join scans all 200 rows of `tags` per article row** — the planner hash-joins
  against a `Seq Scan` of the whole tag table to resolve ~2.4 names. That looked like the obvious
  remaining waste. Two fixes were measured and both rejected: resolving names through `tags_pkey`
  (tag subplan 74 → 102 buffers and 10 → 24 scans of `tags`; statement time indistinguishable), and
  batching the whole page's tags in one grouped pass (0.2 ms of tag work instead of ~0.5, but 190 → 218
  buffers at offset 0, plus a second column list to maintain). It stays as written. An `EXISTS`-style
  `following` folded to the constant it already is on the feed was also measured and dropped: it is a
  provable equivalence, but the feed is ~8% of requests and the probe it removes is 4 buffers.
- **The per-row probe on the comments path is the `users` join, not `following`.** With the `following`
  probe pulled up and accounted for, `COMMENTS_PAGE`'s remaining cost is
  `Index Scan using users_pkey on users u (actual rows=1.00 loops=100)` — 300 of the statement's 310
  buffers, one `users_pkey` descent per comment row. Recorded as a lead, **not acted on here**: it is
  the profile join every profile-bearing endpoint pays, and folding it into a batched pass is the same
  kind of rewrite already measured and rejected for the tag array. It is a real cost and the next
  thing worth an audit, but it is not this issue's probe and it does not belong in a verdict about
  `following`.

## Why compression is off

`SPEC.md` notes Nginx gzips JSON over 1 KB, and serving directly (rule 10) gives that up — so it looks
like free money to reclaim. It isn't, and the arithmetic is worth writing down:

- A 10-row article list is roughly 5–10 KB of highly compressible JSON; `limit=10` with offset ≤ 40
  keeps responses small.
- Tomcat compresses at the JDK default level (~6) for every response over `compressMinSize`. Nginx's
  default `gzip_comp_level` is **1**, so the app-side path is several times more expensive per byte
  than the baseline it would be replacing. Spring Boot exposes no level knob for Tomcat.
- At ~5.5k req/s that lands in the order of a core of the shared 8, spent to shrink traffic on a link
  that is not the constraint — the load generator runs on separate machines and think time is 7 s.

CPU is the scored resource here; bytes on the wire are not. So it is left off, and if it is ever tried
it should be tried as a measurement on a box, not reasoned about.

## Explicitly not doing

Caching, extra indexes, async writes (rules 3/5/6). No WebFlux: this is blocking JDBC, which
suits virtual threads. No JVM-flag shotgunning — G1 and compact object headers are JDK 27
defaults already.