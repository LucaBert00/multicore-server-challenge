#!/usr/bin/env bash
# Runs the server in the foreground. Config: DATABASE_URL, JWT_SECRET, HOST, PORT.
# Tuned for the 8-core challenge box (direct front):
#  - Hikari pool 3 x cores (24 on 8 cores), not 10 x cores. The pool is a ceiling, and the box shares
#    8 cores with Postgres (pgtuned, max_connections = 100). Sub-millisecond queries at the target
#    ~5.5k req/s need far less concurrency than 80 backends, and 80 resident Postgres backends on 8
#    cores mostly buy scheduler thrash and idle memory. Capped at 95 to respect SPEC rule 2.
#  - Heap capped at 3g (box has 16 GB shared with Postgres): leaves room for Postgres buffers + OS
#    page cache; G1 is JDK 27's default everywhere.
#  - MaxGCPauseMillis=100 keeps p99 < 1 s achievable under load.
set -euo pipefail
cd "$(dirname "$0")"
CORES="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 8)"
POOL="$(( 3 * CORES ))"
if [ "$POOL" -gt 95 ]; then POOL=95; fi
export DB_POOL_SIZE="${DB_POOL_SIZE:-$POOL}"
if [ -x /opt/jdk/bin/java ]; then JAVA=/opt/jdk/bin/java; else JAVA="$JAVA_HOME/bin/java"; fi   # install.sh's JDK 27 first
exec "$JAVA" -Xms1g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -jar target/conduit.jar
