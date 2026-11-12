-- Idempotency keys for mutating actions (NF04): a repeated action with the same key
-- returns the stored response instead of producing a second business result.

CREATE TABLE idempotency_key (
    user_id         uuid         NOT NULL REFERENCES app_user (id),
    idem_key        varchar(100) NOT NULL,
    operation       text         NOT NULL,
    request_hash    char(64)     NOT NULL,
    response_status integer,
    response_body   jsonb,
    resource_id     uuid,
    created_at      timestamptz  NOT NULL,

    PRIMARY KEY (user_id, idem_key),
    CONSTRAINT idempotency_key_not_blank CHECK (btrim(idem_key) <> '')
);
