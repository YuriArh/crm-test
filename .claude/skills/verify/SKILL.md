---
name: verify
description: How to run dispatch and drive its HTTP API to observe a change at runtime (build, launch, browser-like curl session with CSRF, DB checks).
---

# Verifying dispatch at runtime

## Launch
```bash
make reset && make up && make wait     # clean DB, build image from current code, wait for /actuator/health
docker compose logs dispatch | grep -E 'Successfully applied|Started'
```
- Host ports come from `.env`: on this machine Postgres is on **5433** (5432 is taken by another project), API on 8080.
- `make up` rebuilds the image, so the container always runs the working tree.

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

## Gotchas
- Keep state (cookie jars, ids) in one directory you control across shell calls; `mktemp -d` per call loses the session.
- A request without any cookies gets 403 (CSRF) before authentication is checked, not 401.
