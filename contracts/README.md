# Contracts

OpenAPI descriptions of the exchange contracts:

- `dispatch-api.yaml` — API of `dispatch`, used by the web client;
- `carrier-api.yaml` — external carrier API (implemented by `carrier-emulator`, called by `dispatch`).

`dispatch` and `carrier-emulator` do not share Java classes: the emulator stands
in for an external system, so the contract lives here as the single agreement
between the two sides.
