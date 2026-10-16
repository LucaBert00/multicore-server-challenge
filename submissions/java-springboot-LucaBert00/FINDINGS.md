# Findings — where the time actually goes

Notes from reading `bench/` and `WORKLOAD.md` in the challenge repo, before optimising.
No numbers here are measured on a box; they are what the load generator's own code says it
will send. The point is to aim the work, not to claim a result.

## Status

Conformance suite passes 197/197 (CI run `38938085881`, Ubuntu 24.04 + Postgres 18).
Nothing about performance has been measured yet.

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

1. **Merge the page + count into one statement** (~32% of requests). Preserve the `LIMIT 1000`
   cap; return the count on an empty page. `Sql.java`, `ArticleController.java`.
2. **Single round trip for comments** (~20%). `CommentController.java:30-32` resolves slug→id
   before fetching. Must still 404 on an unknown article but return an empty list for an
   article with no comments — a `LEFT JOIN LATERAL` onto a one-row driver does that.
3. **Enable response compression.** `SPEC.md`: "Nginx gzips JSON over 1 KB". Serving directly
   (rule 10) gives that up unless the app does it, and no compression is configured today.
4. **Right-size the pool.** `start.sh` computes `10 x cores` = 80 backends against
   `max_connections = 100` on a box that shares 8 cores with Postgres. `connection-timeout=5000`
   also turns saturation into 5-second stalls, which fails the p99 gate before the error gate.

## Explicitly not doing

Caching, extra indexes, async writes (rules 3/5/6). No WebFlux: this is blocking JDBC, which
suits virtual threads. No JVM-flag shotgunning — G1 and compact object headers are JDK 27
defaults already.