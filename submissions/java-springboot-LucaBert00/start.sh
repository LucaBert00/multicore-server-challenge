#!/usr/bin/env bash
# Runs the server in the foreground. Config: DATABASE_URL, JWT_SECRET, HOST, PORT.
# Tuned for the 8-core challenge box (direct front):
#  - Hikari pool min(95, 10 x cores): video baseline was 10 x cores (80 on 8 cores);
#    capped at 95 to respect max_connections = 100 (SPEC rule 2).
#  - Heap capped at 3g (box has 16 GB shared with Postgres): leaves room for
#    Postgres buffers + OS page cache; G1 is JDK 27's default everywhere.
#  - MaxGCPauseMillis=100 keeps p99 < 1 s achievable under load.
set -euo pipefail
cd "$(dirname "$0")"
CORES="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 8)"
POOL="$(( 10 * CORES ))"
if [ "$POOL" -gt 95 ]; then POOL=95; fi
export DB_POOL_SIZE="${DB_POOL_SIZE:-$POOL}"
if [ -x /opt/jdk/bin/java ]; then JAVA=/opt/jdk/bin/java; else JAVA="$JAVA_HOME/bin/java"; fi   # install.sh's JDK 27 first
exec "$JAVA" -Xms1g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -jar target/conduit.jar
