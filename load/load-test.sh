#!/usr/bin/env bash
# make load-test: the measured load run and its report (docs/specs/load-test.md §5-§7).
#
# Checks the stand, samples docker stats while k6 runs the load, waits until the carrier has executed what the run
# created, takes the data for the report from the database and builds load/out/report.md. The exit code is that of
# k6: non-zero when a threshold is crossed (the report is written anyway).
#
# Usage: PROFILE=full|smoke K6_IMAGE=grafana/k6:<version> load/load-test.sh   (needs docker, jq, node >= 22.6, git)
set -euo pipefail

cd "$(dirname "$0")/.."

PROFILE="${PROFILE:-full}"
K6_IMAGE="${K6_IMAGE:?K6_IMAGE is required (make load-test passes it)}"
PROJECT=exchange-load
OUT=load/out
DISPATCH_URL="${LOAD_DISPATCH_URL:-http://localhost:18080}"
CARRIER_URL="${LOAD_CARRIER_URL:-http://localhost:18081}"
DRAIN_TIMEOUT_S=900
STATS_INTERVAL_S=5

for tool in jq node git docker curl; do
  command -v "$tool" >/dev/null || { echo "load-test: $tool is required" >&2; exit 1; }
done
node -e 'const [a, b] = process.versions.node.split(".").map(Number); process.exit(a > 22 || (a === 22 && b >= 6) ? 0 : 1)' \
  || { echo "load-test: node >= 22.6 is required (the report runs TypeScript directly)" >&2; exit 1; }
jq -e --arg p "$PROFILE" '.[$p]' load/profiles.json >/dev/null \
  || { echo "load-test: unknown PROFILE $PROFILE (see load/profiles.json)" >&2; exit 1; }

compose() { docker compose -p "$PROJECT" "$@"; }
dispatch_db() { compose exec -T postgres psql -U "${DB_USER:-dispatch}" -d "${DB_NAME:-dispatch}" -At "$@"; }

# 1. The demo stack would share the CPUs of Docker with the load stack. A short smoke run measures nothing: it may.
if [ "$PROFILE" != smoke ] && [ -n "$(docker compose -p exchange ps -q 2>/dev/null)" ]; then
  echo "load-test: the demo stack (project exchange) is running and would share the CPUs of Docker with the load stack." >&2
  echo "           Stop it: make down" >&2
  exit 1
fi

# 2. The load stack is up.
for url in "$DISPATCH_URL" "$CARRIER_URL"; do
  curl -sf "$url/actuator/health" >/dev/null || { echo "load-test: not healthy at $url - make load-up" >&2; exit 1; }
done

# 3. The carrier works in its normal mode: every fault mode is off (and recorded in the report).
mkdir -p "$OUT"
rm -f "$OUT"/run.json "$OUT"/env.json "$OUT"/compose.json "$OUT"/faults.json "$OUT"/dataset.csv \
  "$OUT"/docker-stats.jsonl "$OUT"/requests.json "$OUT"/summary.json "$OUT"/exec-times.csv "$OUT"/k6.log "$OUT"/report.md
curl -sf -X PUT -H 'Content-Type: application/json' \
  -d '{"unavailable":false,"loseResponse":false,"duplicate":false,"delayed":false,"malformed":false}' \
  "$CARRIER_URL/admin/faults" >/dev/null
curl -sf "$CARRIER_URL/admin/faults" >"$OUT/faults.json"

# 4. The data set is there, settled, without open problems (a request with a problem would be reconciled in the
#    background and change under the clients), and it has the size of the profile (the registry of NF08 has 5000).
requests=$(dispatch_db -c "select count(*) from delivery_request")
[ "$requests" -gt 0 ] || { echo "load-test: no data set - make load-seed" >&2; exit 1; }
unsettled=$(dispatch_db -c "select count(*) from delivery_request where business_status in ('AWAITING','IN_PROGRESS')")
[ "$unsettled" -eq 0 ] || { echo "load-test: $unsettled requests have no result yet: wait for the exchange" >&2; exit 1; }
open_problems=$(dispatch_db -c "select count(*) from problem where status = 'OPEN'")
[ "$open_problems" -eq 0 ] || { echo "load-test: $open_problems open problems: make load-reset && make load-up && make load-seed" >&2; exit 1; }
seed_size=$(jq -r --arg p "$PROFILE" '.[$p].seedSize' load/profiles.json)
if [ "$requests" -ne "$seed_size" ]; then
  message="the registry has $requests requests, the profile $PROFILE seeds $seed_size (an earlier run added some)"
  if [ "$PROFILE" = smoke ]; then
    echo "load-test: warning: $message"
  else
    echo "load-test: $message: make load-reset && make load-up && make load-seed" >&2
    exit 1
  fi
fi

# 5. The environment and the limits (no passwords: only what the report prints).
host_cpus=$(sysctl -n hw.ncpu 2>/dev/null || nproc)
host_memory=$(sysctl -n hw.memsize 2>/dev/null || awk '/MemTotal/ {print $2 * 1024}' /proc/meminfo)
docker_info=$(docker info --format '{{json .}}')
k6_version=$(docker run --rm "$K6_IMAGE" version | head -n 1)
printenv_dispatch() { compose exec -T dispatch printenv "$1" 2>/dev/null || true; }
jq -n \
  --arg date "$(date -u +%FT%TZ)" \
  --arg commit "$(git rev-parse --short HEAD)$(git diff --quiet HEAD -- || echo ' (uncommitted changes)')" \
  --arg k6 "$K6_IMAGE - $k6_version" \
  --arg os "$(uname -sr)" --argjson hostCpus "$host_cpus" --argjson hostMemory "$host_memory" \
  --argjson docker "$docker_info" \
  --arg timezone "$(printenv_dispatch APP_TIMEZONE)" \
  --arg pool "$(printenv_dispatch DB_POOL_SIZE)" \
  --arg jvm "$(printenv_dispatch JAVA_TOOL_OPTIONS)" \
  '{date: $date, commit: $commit, k6Image: $k6,
    host: {os: $os, cpus: $hostCpus, memoryBytes: $hostMemory},
    docker: {version: $docker.ServerVersion, cpus: $docker.NCPU, memoryBytes: $docker.MemTotal},
    app: {timezone: $timezone, dbPoolSize: (if $pool == "" then "10 (default)" else $pool end), jvmOptions: $jvm}}' \
  >"$OUT/env.json"
compose config --format json \
  | jq '{services: (.services | map_values({deploy: {resources: {limits: .deploy.resources.limits}}}))}' \
  >"$OUT/compose.json"
dispatch_db -c "COPY (
    select u.username, r.business_status, count(*)
      from delivery_request r join app_user u on u.id = r.owner_id
     group by 1, 2 order by 1, 2) TO STDOUT WITH CSV HEADER" >"$OUT/dataset.csv"

# 6. docker stats every few seconds while the script runs; whatever way it ends, the sampling stops.
sample_stats() {
  while true; do
    compose ps -q | xargs docker stats --no-stream --format '{{json .}}' >>"$OUT/docker-stats.jsonl" || true
    sleep "$STATS_INTERVAL_S"
  done
}
STATS_PID=
cleanup() {
  if [ -n "$STATS_PID" ]; then
    kill "$STATS_PID" 2>/dev/null || true
    wait "$STATS_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
sample_stats &
STATS_PID=$!

# 7. The load. A failed threshold is not a failure of the script: the report is still built.
RUN_ID=$(date +%s)
started_at=$(date -u +%FT%TZ)
set +e
make --no-print-directory load-run PROFILE="$PROFILE" RUN_ID="$RUN_ID" 2>&1 | tee "$OUT/k6.log"
k6_exit=${PIPESTATUS[0]}
set -e
finished_at=$(date -u +%FT%TZ)

# 8. The requests the run created: wait until the carrier has executed them (not part of the API times).
run_requests="from idempotency_key k join delivery_request r on r.id = k.resource_id
              where k.operation = 'CREATE_REQUEST' and k.idem_key like 'load-${RUN_ID}-%'"
pending() { dispatch_db -c "select count(*) $run_requests and r.business_status in ('AWAITING','IN_PROGRESS')"; }
drain_started=$(date +%s)
left=$(pending)
while [ "$left" -gt 0 ] && [ $(($(date +%s) - drain_started)) -lt "$DRAIN_TIMEOUT_S" ]; do
  echo "Waiting for the carrier: $left requests of the run have no result yet"
  sleep 5
  left=$(pending)
done
drain_seconds=$(($(date +%s) - drain_started))

# 9. The times of the exchange for the report: seconds from the creation of a request to each event.
dispatch_db -c "COPY (
    select r.number,
           extract(epoch from o.acknowledged_at - r.created_at) as ack_s,
           extract(epoch from r.result_at - r.created_at) as exec_s,
           extract(epoch from i.processed_at - r.created_at) as applied_s
      from idempotency_key k
      join delivery_request r on r.id = k.resource_id
      join dispatch_operation o on o.request_id = r.id
      left join inbox i on i.id = o.applied_inbox_id
     where k.operation = 'CREATE_REQUEST' and k.idem_key like 'load-${RUN_ID}-%'
     order by r.number) TO STDOUT WITH CSV HEADER" >"$OUT/exec-times.csv"

jq -n --arg profile "$PROFILE" --arg runId "$RUN_ID" --argjson exit "$k6_exit" \
  --arg started "$started_at" --arg finished "$finished_at" \
  --argjson drain "$drain_seconds" --argjson left "$left" \
  '{profile: $profile, runId: $runId, k6Exit: $exit, startedAt: $started, finishedAt: $finished,
    drain: {seconds: $drain, pending: $left}}' >"$OUT/run.json"

cleanup
STATS_PID=
node --experimental-strip-types --no-warnings load/report/main.ts "$OUT"
exit "$k6_exit"
