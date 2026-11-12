-- Delivery requests, their dispatch operation (also the send queue) and send attempts.

CREATE SEQUENCE delivery_request_number_seq;

CREATE TABLE delivery_request (
    id                uuid          PRIMARY KEY,
    number            bigint        NOT NULL UNIQUE DEFAULT nextval('delivery_request_number_seq'),
    service_id        uuid          NOT NULL REFERENCES service (id),
    owner_id          uuid          NOT NULL,
    assignee_id       uuid,

    -- Content: immutable after creation (enforced by the application).
    cargo_description varchar(200)  NOT NULL,
    weight_kg         numeric(8, 1) NOT NULL,
    delivery_address  varchar(500)  NOT NULL,
    deadline          date          NOT NULL,

    comment           varchar(2000),

    -- Business result reported by the carrier.
    business_status   text          NOT NULL DEFAULT 'AWAITING',
    tracking_number   text,
    price             numeric(12, 2),
    rejection_reason  text,
    result_at         timestamptz,

    created_at        timestamptz   NOT NULL,
    updated_at        timestamptz   NOT NULL,
    -- Bumped by every writer (users and the result worker), so no change is overwritten silently.
    version           bigint        NOT NULL,

    -- Author and assignee must belong to the request's service.
    CONSTRAINT delivery_request_owner_same_service
        FOREIGN KEY (owner_id, service_id) REFERENCES app_user (id, service_id),
    CONSTRAINT delivery_request_assignee_same_service
        FOREIGN KEY (assignee_id, service_id) REFERENCES app_user (id, service_id),

    CONSTRAINT delivery_request_cargo_not_blank CHECK (btrim(cargo_description) <> ''),
    CONSTRAINT delivery_request_address_not_blank CHECK (btrim(delivery_address) <> ''),
    CONSTRAINT delivery_request_weight_range CHECK (weight_kg BETWEEN 0.1 AND 20000),
    CONSTRAINT delivery_request_price_non_negative CHECK (price IS NULL OR price >= 0),
    CONSTRAINT delivery_request_business_status_valid
        CHECK (business_status IN ('AWAITING', 'IN_PROGRESS', 'SUCCEEDED', 'REJECTED')),
    CONSTRAINT delivery_request_succeeded_has_result
        CHECK (business_status <> 'SUCCEEDED'
               OR (tracking_number IS NOT NULL AND price IS NOT NULL AND result_at IS NOT NULL)),
    CONSTRAINT delivery_request_rejected_has_reason
        CHECK (business_status <> 'REJECTED'
               OR (rejection_reason IS NOT NULL AND result_at IS NOT NULL))
);

-- Registry: operator scope (service) and initiator scope (owner), newest first.
CREATE INDEX delivery_request_service_status_created_idx
    ON delivery_request (service_id, business_status, created_at DESC);
CREATE INDEX delivery_request_owner_created_idx
    ON delivery_request (owner_id, created_at DESC);
CREATE INDEX delivery_request_assignee_idx
    ON delivery_request (assignee_id);

CREATE TABLE dispatch_operation (
    request_id       uuid        PRIMARY KEY REFERENCES delivery_request (id),
    -- Business-operation identity sent to the carrier; never changes across retries.
    operation_id     uuid        NOT NULL UNIQUE,
    payload_hash     char(64)    NOT NULL,

    delivery_status  text        NOT NULL DEFAULT 'PENDING',
    attempts         integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL,
    locked_until     timestamptz,
    last_error       text,

    acknowledged_at  timestamptz,
    acknowledged_via text,

    last_applied_seq integer     NOT NULL DEFAULT 0,

    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,

    CONSTRAINT dispatch_operation_delivery_status_valid
        CHECK (delivery_status IN ('PENDING', 'SENDING', 'RETRY_WAIT', 'UNKNOWN', 'ACKNOWLEDGED', 'FAILED')),
    CONSTRAINT dispatch_operation_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT dispatch_operation_last_applied_seq_non_negative CHECK (last_applied_seq >= 0),
    CONSTRAINT dispatch_operation_acknowledged_via_valid
        CHECK (acknowledged_via IS NULL OR acknowledged_via IN ('RESPONSE', 'RESULT', 'RECONCILIATION')),
    CONSTRAINT dispatch_operation_acknowledged_consistent
        CHECK ((acknowledged_at IS NULL) = (acknowledged_via IS NULL)),
    CONSTRAINT dispatch_operation_sending_is_locked
        CHECK (delivery_status <> 'SENDING' OR locked_until IS NOT NULL)
);

-- Send queue: operations waiting for a (re)try.
CREATE INDEX dispatch_operation_queue_idx
    ON dispatch_operation (next_attempt_at)
    WHERE delivery_status IN ('PENDING', 'RETRY_WAIT', 'UNKNOWN');
-- Recovery of operations left in SENDING after a crash.
CREATE INDEX dispatch_operation_lease_idx
    ON dispatch_operation (locked_until)
    WHERE delivery_status = 'SENDING';
-- Registry filter by delivery status.
CREATE INDEX dispatch_operation_delivery_status_idx
    ON dispatch_operation (delivery_status);

CREATE TABLE exchange_attempt (
    id           uuid        PRIMARY KEY,
    operation_id uuid        NOT NULL REFERENCES dispatch_operation (operation_id),
    attempt_no   integer     NOT NULL,
    started_at   timestamptz NOT NULL,
    finished_at  timestamptz,
    outcome      text,
    http_status  integer,
    error        text,
    duration_ms  integer,

    CONSTRAINT exchange_attempt_number_unique UNIQUE (operation_id, attempt_no),
    CONSTRAINT exchange_attempt_number_positive CHECK (attempt_no > 0),
    CONSTRAINT exchange_attempt_outcome_valid
        CHECK (outcome IS NULL
               OR outcome IN ('ACKNOWLEDGED', 'TEMPORARY_ERROR', 'UNKNOWN', 'PERMANENT_ERROR', 'PAYLOAD_CONFLICT')),
    CONSTRAINT exchange_attempt_duration_non_negative CHECK (duration_ms IS NULL OR duration_ms >= 0)
);
