#!/bin/sh
# Creates the carrier-emulator's own database and user next to the dispatch database.
# PostgreSQL runs init scripts only on an empty data volume: on an existing installation
# run `make reset` once.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
CREATE ROLE "${CARRIER_DB_USER}" LOGIN PASSWORD '${CARRIER_DB_PASSWORD}';
CREATE DATABASE "${CARRIER_DB_NAME}" OWNER "${CARRIER_DB_USER}";
EOSQL
