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