# Contracts

OpenAPI descriptions of the exchange contracts:

- `carrier-api.yaml` — API of the external carrier: implemented by `carrier-emulator`, called by
  `dispatch`. Also describes the exchange rules (operation identity, idempotent submit, result feed,
  applicability of results, reconciliation) and the emulator's own `admin` endpoints;
- `dispatch-api.yaml` — API of `dispatch` for the web client (not written yet).

Rules:

- The YAML is the source of truth. `dispatch` and `carrier-emulator` do **not** share Java classes:
  the emulator stands in for an external system, so each side writes its own DTOs from this file
  by hand (no code generation).
- Change the contract first, then both sides.
- Lint after every change: `make contracts-lint` (Redocly CLI in Docker, config in `redocly.yaml`).
  Examples are validated against their schemas; a wrong example fails the lint.
