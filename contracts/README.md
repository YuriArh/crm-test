# Contracts

OpenAPI descriptions of the exchange contracts:

- `app-api.yaml` — backend API used by the web client;
- `emulator-api.yaml` — external service API (implemented by the emulator, called by the backend).

The backend and the emulator do not share Java classes: the emulator stands in
for an external system, so the contract lives here as the single agreement
between the two sides.
