---
name: verify
description: How to run dispatch and carrier-emulator and drive their HTTP APIs to observe a change at runtime (build, launch, browser-like curl session with CSRF, emulator flows, DB checks).
---

# Verifying dispatch and carrier-emulator at runtime

## Launch
```bash
make reset && make up && make wait     # clean DB, build images from current code, wait for both /actuator/health
docker compose logs dispatch carrier-emulator | grep -E 'Successfully applied|Started|ERROR'
```
- Host ports come from `.env`: on this machine Postgres is on **5433** (5432 is taken by another project), dispatch on 8080, carrier-emulator on 8081.
- `make up` rebuilds the images, so the containers always run the working tree.
- If carrier-emulator does not start (`make wait` times out on 8081, logs say database/role `carrier` does not exist), the volume predates `docker/postgres/init`: run `make reset` (init scripts run only on an empty volume).

## Drive the API like a browser
Every mutating call needs the `X-XSRF-TOKEN` header = value of the `XSRF-TOKEN` cookie; the token **rotates after login**, so re-read it from the cookie jar before each call. One cookie jar per user.
```bash
JAR=$(mktemp); API=http://localhost:8080
tok() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR" 2>/dev/null; }
curl -s -c $JAR -b $JAR $API/api/auth/csrf >/dev/null
curl -s -c $JAR -b $JAR -H "X-XSRF-TOKEN: $(tok)" -H 'Content-Type: application/json' \
     -d '{"username":"north.initiator","password":"demo1234"}' $API/api/auth/login
curl -s -c $JAR -b $JAR -H "X-XSRF-TOKEN: $(tok)" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
     -d '{"cargoDescription":"Pallets","weightKg":12.5,"deliveryAddress":"Main st 1","deadline":"<today+N in Europe/Moscow>"}' \
     $API/api/requests
```
Demo users (password `demo1234`): `north.initiator`, `north.operator` (North), `south.initiator` (South).

## Flows worth driving
- Auth: wrong password / unknown user → identical 401 `BAD_CREDENTIALS`; no CSRF header → 403; logout → `/me` 401.
- Create: replay with same key (`Idempotent-Replayed: true`), same key + other body → 422, parallel same-key calls → one row.
- Scope: other initiator / other service → 404 on card and absent from list; `exchange` block only for operators.
- PATCH: stale `version` → 409 with `current`; same value → 200 without version bump; omitted field kept, `null` clears.
- Restart: `docker compose restart dispatch` → data persists, session is lost (401) by design.

## Check the database
```bash
docker compose exec -T postgres psql -U dispatch -d dispatch -tAc "select delivery_status from dispatch_operation"
```

## carrier-emulator (port 8081, no auth, plain JSON)
`make up` starts it too; `make wait` waits for both services. Its DB is `carrier` (user `carrier`), created by `docker/postgres/init` only on an empty volume.
```bash
C=http://localhost:8081; ID=$(uuidgen | tr A-Z a-z)
DEADLINE=$(TZ=Europe/Moscow date -v+7d +%F 2>/dev/null || TZ=Europe/Moscow date -d '+7 days' +%F)   # future date, as dispatch would send
# The feed is not empty after earlier runs: remember its end before submitting (pages of at most 100).
END=0; while :; do N=$(curl -s "$C/results?after=$END&limit=100" | python3 -c 'import json,sys; i=json.load(sys.stdin)["items"]; print(i[-1]["feedSeq"] if i else "")'); [ -z "$N" ] && break; END=$N; done
curl -s -H 'Content-Type: application/json' \
     -d "{\"operationId\":\"$ID\",\"cargoDescription\":\"Pallets\",\"weightKg\":12.5,\"deliveryAddress\":\"Main st 1\",\"deadline\":\"$DEADLINE\"}" $C/operations
# Poll until executed instead of a fixed sleep (the executor works through a backlog in batches of 50).
for i in $(seq 1 30); do curl -s $C/operations/$ID | grep -q '"state":"EXECUTED"' && break; sleep 0.5; done
curl -s $C/operations/$ID; curl -s "$C/admin/operations?operationId=$ID"
curl -s "$C/results?after=$END"            # only entries published after the submit
docker compose exec -T postgres psql -U carrier -d carrier -tAc \
     "select operation_id, state, received_count, execution_count from operation where operation_id = '$ID'"
```
- State is `ACCEPTED` right after submit and `EXECUTED` with a result about a second later (longer with a backlog — always poll).
- Flows: repeat (also `12.50`, padded text) → 202 and `receivedCount+1`, `executionCount` stays 1; other content → 409; weight > 10000 or `тест-отказ` in the address → `REJECTED`; 20 parallel submits of one id → one row; `limit`/`after`/uuid validation → 400 `{code,message}`. A string weight (`"12.5"`) is accepted on purpose.
- Recovery: stop the emulator, insert an `ACCEPTED` row with all required columns, start it → the row gets executed. Use a fresh id (`R`) and never submit the same content for it via the API afterwards: `payload_hash` here is a placeholder, so a later POST would get 409.
  ```bash
  R=$(uuidgen | tr A-Z a-z); docker compose stop carrier-emulator
  docker compose exec -T postgres psql -U carrier -d carrier -tAc "insert into operation (operation_id, cargo_description,
      weight_kg, delivery_address, deadline, payload_hash, state, received_count, execution_count, received_at)
      values ('$R', 'Left over', 3, 'Main st 3', '$DEADLINE', 'placeholder', 'ACCEPTED', 1, 0, now())"
  docker compose start carrier-emulator && make wait
  for i in $(seq 1 30); do curl -s $C/operations/$R | grep -q '"state":"EXECUTED"' && break; sleep 0.5; done
  curl -s $C/operations/$R   # → EXECUTED, SUCCEEDED, 375.00
  ```

## Gotchas
- Keep state (cookie jars, ids) in one directory you control across shell calls; `mktemp -d` per call loses the session.
- A request without any cookies gets 403 (CSRF) before authentication is checked, not 401.
