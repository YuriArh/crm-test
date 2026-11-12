-- Operations received from dispatch: one row per operationId (at most one execution).
CREATE TABLE operation (
    operation_id        uuid           NOT NULL,
    cargo_description   text           NOT NULL,
    weight_kg           numeric(6, 1)  NOT NULL,
    delivery_address    text           NOT NULL,
    deadline            date           NOT NULL,
    payload_hash        text           NOT NULL,
    state               text           NOT NULL,
    result_status       text,
    tracking_number     text,
    price               numeric(12, 2),
    reason              text,
    received_count      integer        NOT NULL,
    execution_count     integer        NOT NULL,
    received_at         timestamptz    NOT NULL,
    executed_at         timestamptz,
    CONSTRAINT operation_pk PRIMARY KEY (operation_id),
    CONSTRAINT operation_state_known CHECK (state IN ('ACCEPTED', 'EXECUTED')),
    CONSTRAINT operation_result_status_known CHECK (result_status IN ('SUCCEEDED', 'REJECTED')),
    CONSTRAINT operation_received_count_positive CHECK (received_count >= 1),
    CONSTRAINT operation_execution_at_most_once CHECK (execution_count IN (0, 1)),
    -- Each rule in its own constraint, so a violation names the rule that was broken.
    CONSTRAINT operation_accepted_not_executed CHECK (
        state <> 'ACCEPTED' OR (execution_count = 0 AND result_status IS NULL AND executed_at IS NULL)),
    CONSTRAINT operation_executed_has_result CHECK (
        state <> 'EXECUTED' OR (execution_count >= 1 AND result_status IS NOT NULL AND executed_at IS NOT NULL)),
    CONSTRAINT operation_weight_range CHECK (weight_kg BETWEEN 0.1 AND 20000),
    CONSTRAINT operation_price_not_negative CHECK (price >= 0)
);

-- Executor queue.
CREATE INDEX operation_accepted_idx ON operation (received_at) WHERE state = 'ACCEPTED';
-- Admin list, newest first.
CREATE INDEX operation_received_at_idx ON operation (received_at DESC);

-- Result feed read by dispatch with GET /results?after=N. Written by a single executor thread,
-- so entries become visible in feed_seq order and a reader's cursor never skips one
-- (the numbers themselves may have gaps after a rollback).
-- message_id is not unique on purpose: the duplicate fault mode (next step) publishes a message twice.
CREATE TABLE feed_entry (
    feed_seq        bigserial      NOT NULL,
    message_id      uuid           NOT NULL,
    operation_id    uuid           NOT NULL,
    sequence        integer        NOT NULL,
    status          text           NOT NULL,
    tracking_number text,
    price           numeric(12, 2),
    reason          text,
    occurred_at     timestamptz    NOT NULL,
    CONSTRAINT feed_entry_pk PRIMARY KEY (feed_seq),
    CONSTRAINT feed_entry_operation_fk FOREIGN KEY (operation_id) REFERENCES operation (operation_id),
    CONSTRAINT feed_entry_sequence_positive CHECK (sequence >= 1),
    CONSTRAINT feed_entry_status_known CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'REJECTED'))
);

-- Latest result of an operation (reconciliation, admin).
CREATE INDEX feed_entry_operation_idx ON feed_entry (operation_id, sequence DESC, feed_seq);
