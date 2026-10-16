# Conduit: Spring Boot 4 + virtual threads (direct front, tuned)

**Front: direct.** Serves `0.0.0.0:80` on the benchmark box (no Nginx).

This is a new Spring Boot implementation of the
[multicore-server-challenge](https://github.com/arjaythedev/multicore-server-challenge)
Conduit API (`SPEC.md`: 19 endpoints, reference SQL verbatim, 197 conformance checks).
Java sources are copied verbatim from the video baseline `examples/java`
(Spring MVC + virtual threads, JdbcTemplate/HikariCP); all tuning is in
config and scripts so conformance behavior is unchanged.

Target to beat (video, 8 vCPU DigitalOcean Premium AMD, unoptimized baselines):

| Stack | 8-core score |
|---|---|
| Go `net/http` + pgx | 22,000 users (best) |
| Java Spring Boot 4 + virtual threads | 21,000 users |
| Rust axum + tokio + sqlx | 19,000 users |
| Python FastAPI + Uvicorn | 10,000 users |

## Versions

| Piece | Version |
|---|---|
| JDK | 27 (`/opt/jdk`, Temurin 27+35 via `install.sh`) |
| Spring Boot | 4.1.1 (`spring-boot-starter-webmvc`, `spring-boot-starter-jdbc`) |
| Tomcat (embedded) | 11.0.24 |
| HikariCP | 7.0.2 |
| PostgreSQL JDBC | 42.7.14 |
| JWT | auth0 `java-jwt` 4.6.1, HS256 |
| bcrypt | spring-security-crypto `BCryptPasswordEncoder(10)` |
| Maven | 3.9.16 via `./mvnw` (wrapper 3.3.4) |

## Why Boot 4 + JDK 27

Most performant pick per research: JDK 25 shows ~15-20% throughput over JDK 21
on web workloads, and JEP 491 (JDK 24+) removes `synchronized` pinning of
virtual-thread carriers — directly relevant to JDBC + Tomcat code paths.
Boot 4 wires virtual threads through Tomcat, async executor and scheduler
with `spring.threads.virtual.enabled=true`.

## Optimizations vs the video baseline (all within challenge rules)

1. **Direct front** (`Front: direct`): saves the ~11% of 8-core CPU the video's
   Nginx consumed. Cost: Tomcat holds tens of thousands of keep-alives itself,
   so `max-connections=50000`, `accept-count=2048`, `keep-alive-timeout=65s`,
   `max-keep-alive-requests=-1`.
2. **Pool size**: `DB_POOL_SIZE = 3 x cores` (24 on 8 cores), respecting `max_connections = 100`.
   The video baseline was an uncapped 10 x cores = 80 backends on a box that shares 8 cores with
   Postgres. The pool is only a ceiling, and sub-millisecond queries do not need 80-way concurrency —
   80 resident backends buy scheduler thrash and idle memory rather than throughput. `keepalive-time=30s`,
   `max-lifetime=10min` avoid stale pooled connections. `connection-timeout` is 1 s so saturation fails
   fast into the error budget instead of stalling past the `p99 < 1 s` gate.
3. **Fewer round trips on the two hottest reads.** `GET /api/articles` (and its `tag`/`author`/
   `favorited`/`feed` variants) and `GET /api/articles/:slug/comments` together carry ~52% of the
   benchmark's requests. Both ran two queries per request. The list now carries its capped total as
   an extra column on the page statement, and the comments query drives off `articles` so it no longer
   resolves slug→id first — one round trip each. The `LIMIT 1000` count cap is preserved (a window
   function would be evaluated before `LIMIT`/`OFFSET` and count the whole table), and the empty-page
   and 404 paths still behave exactly as `SPEC.md` requires. See `FINDINGS.md`.
4. **Heap/GC**: `-Xms1g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=100`.
   Video used no flags (heap = 25% RAM = 4 GB on 16 GB box). Capping at 3 GB
   leaves more RAM for Postgres buffers (2.35 GB seed DB) + OS cache, and the
   pause target protects the p99 < 1 s scoring gate.
5. **Quiet logging**: `logging.level.root=WARN` — no per-request I/O.
6. **SQL otherwise unchanged**: same indexes walk and stop after one page. No response/query caches,
   no JWT/bcrypt caches, writes committed before responding, bcrypt cost 10, HS256 + `exp` verified
   on every request — rules 5/6/7 intact.

## Layout (submission-ready)

```
pom.xml, mvnw, mvnw.cmd, .mvn/
src/main/java/conduit/*.java   (15 files, verbatim baseline)
src/main/resources/application.properties  (tuned)
install.sh  build.sh  start.sh  README.md  .gitignore
```

To submit upstream, copy this folder to
`submissions/<language>-<framework>-<github-username>/` in a fork of
`arjaythedev/multicore-server-challenge` with your `install.sh`/`build.sh`/
`start.sh`/`README.md` at its root.

## Build and run

Builds clean on Temurin 27 + Spring Boot 4.1.1 (verified: `target/conduit.jar`,
25 MB fat jar). The app itself has **never been run** — that needs Postgres 18
loaded with the seed database.

```bash
./build.sh      # ./mvnw -q -B -DskipTests package -> target/conduit.jar
DATABASE_URL=postgres://conduit:conduit@127.0.0.1:5432/conduit JWT_SECRET=... \
  HOST=0.0.0.0 PORT=80 ./start.sh
```

Health check: `GET /api/tags` must return 200 within 60 s of `start.sh`.

> Note: `test/run.sh` (197 checks) and the `bench` loadgen must be run by the
> owner on a Postgres 18 box before submitting. Nothing here has been
> conformance- or load-tested, so no performance claim below is measured.
