#!/usr/bin/env bash
# Demo data through the public API, so every request has a real exchange history (NF01, D07):
# two initiators create requests with different outcomes, the operator takes some of them.
#
# Safe to re-run: each request has a fixed Idempotency-Key, so a repeat creates nothing new
# (the same day: the stored response is replayed; another day: the key is reported as used).
#
# Some requests go through the carrier emulator's fault modes (docs/specs/demo-seed.md). The modes are global, so
# the seed switches them all off when it starts and whenever it exits — on an error and on Ctrl+C too.
#
# Usage: scripts/seed.sh [dispatch base URL]   (default http://localhost:8080; needs curl and jq)
#        CARRIER_BASE_URL — the emulator (default http://localhost:8081)
set -euo pipefail

API="${1:-${E2E_BASE_URL:-http://localhost:8080}}"
CARRIER="${CARRIER_BASE_URL:-http://localhost:8081}"
PASSWORD=demo1234
WORK=$(mktemp -d)

command -v jq >/dev/null || { echo "seed: jq is required" >&2; exit 1; }

FAULTS_OFF='{"unavailable":false,"loseResponse":false,"duplicate":false,"delayed":false,"malformed":false}'

# put_faults <json>: the emulator's switches as a whole object (all five modes).
put_faults() {
  curl -sf -X PUT -H 'Content-Type: application/json' -d "$1" "$CARRIER/admin/faults" >/dev/null \
    || { echo "seed: cannot set the fault modes at $CARRIER" >&2; return 1; }
}

# set_faults <mode...>: switches on exactly these modes and every other one off.
set_faults() {
  local body=$FAULTS_OFF mode
  for mode in "$@"; do body=$(jq -c --arg m "$mode" '.[$m] = true' <<<"$body"); done
  put_faults "$body"
}

faults_off() { put_faults "$FAULTS_OFF"; }

# On every exit: no fault mode is left on, whatever happened. A failure here is reported, the exit code is kept.
cleanup() {
  local status=$?
  faults_off || echo "seed: fault modes may still be on — run make fault-off" >&2
  rm -rf "$WORK"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Starts from a known state: a tester may have left a mode on.
on=$(curl -sf "$CARRIER/admin/faults" | jq -r 'to_entries | map(select(.value)) | map(.key) | join(", ")') \
  || { echo "seed: carrier emulator is not reachable at $CARRIER" >&2; exit 1; }
[ -z "$on" ] || echo "Fault modes were on: $on — switching them off"
faults_off

# Deadline N days from today in the business time zone.
deadline() {
  local zone
  zone=$(curl -sf "$API/api/system/info" | jq -r '.businessTimezone // "Europe/Moscow"')
  TZ="$zone" date -v+"$1"d +%F 2>/dev/null || TZ="$zone" date -d "+$1 days" +%F
}

token() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$WORK/$1.jar"; }

# login <user>: a cookie jar per user in $WORK.
login() {
  local jar="$WORK/$1.jar"
  curl -sf -c "$jar" -b "$jar" "$API/api/auth/csrf" >/dev/null
  curl -sf -c "$jar" -b "$jar" -H "X-XSRF-TOKEN: $(token "$1")" -H 'Content-Type: application/json' \
       -d "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}" "$API/api/auth/login" >/dev/null \
    || { echo "seed: cannot sign in as $1 at $API" >&2; exit 1; }
}

# call <user> <curl args...>: prints the body, then the HTTP status on the last line.
call() {
  local user=$1; shift
  curl -s -c "$WORK/$user.jar" -b "$WORK/$user.jar" -H "X-XSRF-TOKEN: $(token "$user")" \
       -H 'Content-Type: application/json' -w '\n%{http_code}' "$@"
}

# An ISO instant of dispatch as epoch seconds (fractions dropped).
EPOCH='sub("\\.[0-9]+"; "") | fromdateiso8601'

# The start of this run by dispatch's clock, which also stamps createdAt — not the clock of this machine.
RUN_STARTED=$(curl -sf "$API/api/system/info" | jq -r ".serverTime | $EPOCH") \
  || { echo "seed: dispatch is not reachable at $API" >&2; exit 1; }

# create <user> <key> <cargo> <weight> <address> <days>: prints the request id ("" if the key was used another
# day). A request created before this run — replayed the same day, or the key used another day — is marked as
# such (`existed <key>`): a fault scenario cannot be played on it, faults apply at the first receipt only.
create() {
  local user=$1 key=$2 body out status
  body=$(jq -nc --arg c "$3" --argjson w "$4" --arg a "$5" --arg d "$(deadline "$6")" \
         '{cargoDescription: $c, weightKg: $w, deliveryAddress: $a, deadline: $d}')
  out=$(call "$user" -H "Idempotency-Key: $key" -d "$body" "$API/api/requests")
  status=$(tail -n1 <<<"$out")
  case "$status" in
    200|201)
      if sed '$d' <<<"$out" | jq -e --argjson started "$RUN_STARTED" ".createdAt | $EPOCH < \$started" >/dev/null; then
        touch "$WORK/$key.existed"
      fi
      sed '$d' <<<"$out" | jq -r .id ;;
    422) if sed '$d' <<<"$out" | jq -e '.code == "IDEMPOTENCY_KEY_REUSED"' >/dev/null; then
           echo "  $key: already created earlier, skipped" >&2; touch "$WORK/$key.existed"; echo ""
         else echo "seed: $key rejected: $(sed '$d' <<<"$out")" >&2; exit 1; fi ;;
    *) echo "seed: $key failed with HTTP $status: $(sed '$d' <<<"$out")" >&2; exit 1 ;;
  esac
}

# existed <key>: the request of this key was created before this run.
existed() { [ -e "$WORK/$1.existed" ]; }

# The state of a card in one line, for progress and for errors.
STATE='"\(.number): \(.businessStatus), delivery \(.deliveryStatus), attempts \(.exchange.attempts // "—"), problems \([.problems[]? | "\(.kind) \(.status)"] | if length == 0 then "none" else join(", ") end)"'

# wait_card <user> <id> <jq condition> <seconds> <what>: asks for the card every second until the condition holds,
# prints the card. Gives up with the request, what was awaited and the last state seen.
wait_card() {
  local user=$1 id=$2 condition=$3 seconds=$4 what=$5 card=''
  for _ in $(seq 1 "$seconds"); do
    card=$(call "$user" "$API/api/requests/$id" | sed '$d')
    if jq -e "$condition" <<<"$card" >/dev/null 2>&1; then echo "$card"; return; fi
    sleep 1
  done
  echo "seed: request $id: no $what in $seconds s; last state: $(jq -r "$STATE" <<<"$card" 2>/dev/null || echo "$card")" >&2
  exit 1
}

FINAL='.businessStatus == "SUCCEEDED" or .businessStatus == "REJECTED"'

# final_card <user> <id>: waits up to 30 s for the carrier's final result, prints the card.
final_card() { wait_card "$1" "$2" "$FINAL" 30 "final result"; }

# patch <user> <id> <json fields>: edits a request with its current version.
patch() {
  local version out
  version=$(final_card "$1" "$2" | jq .version)
  out=$(call "$1" -X PATCH -d "$(jq -c --argjson v "$version" '. + {version: $v}' <<<"$3")" "$API/api/requests/$2")
  [ "$(tail -n1 <<<"$out")" = 200 ] || { echo "seed: edit of $2 failed: $out" >&2; exit 1; }
}

echo "Seeding demo data at $API"
for user in north.initiator south.initiator north.operator; do login "$user"; done

ids=()
n1=$(create north.initiator seed-north-1 "Паллеты с плиткой"      480     "Северная ул., 12"            5);  ids+=("north.initiator:$n1")
n2=$(create north.initiator seed-north-2 "Офисная мебель"          95.5    "пр. Мира, 7, офис 3"         3);  ids+=("north.initiator:$n2")
n3=$(create north.initiator seed-north-3 "Станок токарный"         12500   "Промзона Северная, корп. 4"  10); ids+=("north.initiator:$n3")
n4=$(create north.initiator seed-north-4 "Документы"               0.5     "тест-отказ, ул. Нет, 0"      1);  ids+=("north.initiator:$n4")
s1=$(create south.initiator seed-south-1 "Фрукты, 40 ящиков"       320     "Южная ул., 3"                2);  ids+=("south.initiator:$s1")
s2=$(create south.initiator seed-south-2 "Стройматериалы"          2300    "пос. Солнечный, д. 15"       14); ids+=("south.initiator:$s2")
s3=$(create south.initiator seed-south-3 "Генератор дизельный"     10500   "Морской порт, причал 2"      7);  ids+=("south.initiator:$s3")

# The operator takes two North requests; the initiator leaves a comment on another one.
operator=$(call north.operator "$API/api/auth/me" | sed '$d' | jq -r .id)
[ -n "$n1" ] && patch north.operator "$n1" "{\"assigneeId\":\"$operator\",\"comment\":\"Согласовать время разгрузки\"}"
[ -n "$n3" ] && patch north.operator "$n3" "{\"assigneeId\":\"$operator\",\"comment\":\"Уточнить у клиента: разбить на две отправки\"}"
[ -n "$n2" ] && patch north.initiator "$n2" '{"comment":"Хрупкое, не кантовать"}'

# Fault modes are global: no request above may still be on its way when one is switched on.
for entry in "${ids[@]}"; do
  [ -n "${entry#*:}" ] && final_card "${entry%%:*}" "${entry#*:}" >/dev/null
done

echo "Created requests and their outcomes:"
for entry in "${ids[@]}"; do
  user=${entry%%:*}; id=${entry#*:}
  [ -n "$id" ] || continue
  final_card "$user" "$id" | jq -r --arg u "$user" \
    '"  \(.number)  \($u)  \(.businessStatus)  \(.result.trackingNumber // .result.rejectionReason)"'
done
echo "Done. Sign in at the web client as north.initiator / north.operator / south.initiator (password $PASSWORD)."
