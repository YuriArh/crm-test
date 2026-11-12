-- Services are the access scope (NF02): every user and every delivery request belongs to one.

CREATE TABLE service (
    id         uuid        PRIMARY KEY,
    code       text        NOT NULL UNIQUE,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT service_code_not_blank CHECK (btrim(code) <> ''),
    CONSTRAINT service_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE app_user (
    id            uuid        PRIMARY KEY,
    username      text        NOT NULL,
    display_name  text        NOT NULL,
    password_hash text        NOT NULL,
    role          text        NOT NULL,
    service_id    uuid        NOT NULL REFERENCES service (id),
    enabled       boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL,
    CONSTRAINT app_user_role_valid CHECK (role IN ('INITIATOR', 'OPERATOR')),
    CONSTRAINT app_user_username_not_blank CHECK (btrim(username) <> ''),
    -- Target for composite foreign keys that guarantee "same service" relations.
    CONSTRAINT app_user_id_service_unique UNIQUE (id, service_id)
);

CREATE UNIQUE INDEX app_user_username_lower_idx ON app_user (lower(username));
CREATE INDEX app_user_service_idx ON app_user (service_id);
