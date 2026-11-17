-- Reconciliation with the carrier (docs/specs/reconciliation.md §2.9): a row per recorded check of one operation,
-- the operation's reconciliation schedule, problems that reconciliation opens, and inbox uniqueness per source.

CREATE TABLE reconciliation (
    id                      uuid          PRIMARY KEY,
    request_id              uuid          NOT NULL REFERENCES delivery_request (id),
    operation_id            uuid          NOT NULL,
    checked_at              timestamptz   NOT NULL,
    run_type                text          NOT NULL,
    -- The operator of a manual check; empty for background checks.
    performed_by            uuid          REFERENCES app_user (id),
    outcome                 text          NOT NULL,
    -- Whether this check confirmed the delivery (moved it to ACKNOWLEDGED).
    acknowledged            boolean       NOT NULL,
    -- Our side when the check was decided.
    delivery_status         text          NOT NULL,
    local_result            text,
    -- The carrier's side; empty when it did not answer (ERROR).
    carrier_state           text,
    carrier_result_status   text,
    carrier_tracking_number text,
    carrier_price           numeric(12, 2),
    carrier_reason          text,
    carrier_sequence        integer,
    carrier_message_id      text,
    carrier_occurred_at     timestamptz,
    -- The carrier's answer verbatim, not truncated: an operator's decision rests on it.
    carrier_response        text,
    details                 text          NOT NULL,
    -- The inbox record of the carrier's result taken over by EXTERNAL_AHEAD.
    inbox_id                uuid          REFERENCES inbox (id),

    CONSTRAINT reconciliation_operation_fk FOREIGN KEY (operation_id) REFERENCES dispatch_operation (operation_id),
    CONSTRAINT reconciliation_run_type_valid CHECK (run_type IN ('STARTUP', 'SCHEDULED', 'MANUAL')),
    CONSTRAINT reconciliation_outcome_valid
        CHECK (outcome IN ('MATCH', 'EXTERNAL_AHEAD', 'EXTERNAL_MISSING', 'CONFLICT', 'ERROR')),
    CONSTRAINT reconciliation_carrier_state_valid CHECK (carrier_state IN ('ACCEPTED', 'EXECUTED', 'NOT_FOUND')),
    CONSTRAINT reconciliation_carrier_result_status_valid CHECK (carrier_result_status IN ('SUCCEEDED', 'REJECTED')),
    CONSTRAINT reconciliation_delivery_status_valid
        CHECK (delivery_status IN ('PENDING', 'SENDING', 'RETRY_WAIT', 'UNKNOWN', 'ACKNOWLEDGED', 'FAILED')),
    -- Each rule in its own constraint and blind to the others' values, so a violation names the rule broken.
    CONSTRAINT reconciliation_manual_has_author CHECK (
        run_type NOT IN ('STARTUP', 'SCHEDULED', 'MANUAL') OR (run_type = 'MANUAL') = (performed_by IS NOT NULL)),
    CONSTRAINT reconciliation_error_without_state CHECK (
        outcome NOT IN ('MATCH', 'EXTERNAL_AHEAD', 'EXTERNAL_MISSING', 'CONFLICT', 'ERROR')
        OR (outcome = 'ERROR') = (carrier_state IS NULL)),
    CONSTRAINT reconciliation_missing_is_not_found CHECK (
        outcome <> 'EXTERNAL_MISSING' OR carrier_state IS NULL
        OR carrier_state NOT IN ('ACCEPTED', 'EXECUTED', 'NOT_FOUND') OR carrier_state = 'NOT_FOUND'),
    -- A conflict needs the carrier to know the operation; its result is optional (ours final, carrier none).
    CONSTRAINT reconciliation_conflict_has_answer CHECK (
        outcome <> 'CONFLICT' OR carrier_state IS NULL
        OR carrier_state NOT IN ('ACCEPTED', 'EXECUTED', 'NOT_FOUND') OR carrier_state IN ('ACCEPTED', 'EXECUTED')),
    CONSTRAINT reconciliation_ahead_has_result CHECK (
        outcome <> 'EXTERNAL_AHEAD' OR (carrier_result_status IS NOT NULL AND inbox_id IS NOT NULL)),
    CONSTRAINT reconciliation_result_needs_executed CHECK (
        carrier_result_status IS NULL OR coalesce(carrier_state, '') = 'EXECUTED'),
    CONSTRAINT reconciliation_result_complete CHECK (
        CASE
            WHEN carrier_result_status IS NULL THEN
                carrier_tracking_number IS NULL AND carrier_price IS NULL AND carrier_reason IS NULL
                AND carrier_sequence IS NULL AND carrier_message_id IS NULL AND carrier_occurred_at IS NULL
            WHEN carrier_result_status = 'SUCCEEDED' THEN
                carrier_tracking_number IS NOT NULL AND carrier_price IS NOT NULL
                AND carrier_sequence IS NOT NULL AND carrier_message_id IS NOT NULL AND carrier_occurred_at IS NOT NULL
            WHEN carrier_result_status = 'REJECTED' THEN
                carrier_reason IS NOT NULL
                AND carrier_sequence IS NOT NULL AND carrier_message_id IS NOT NULL AND carrier_occurred_at IS NOT NULL
            ELSE true
        END),
    CONSTRAINT reconciliation_acknowledged_has_answer CHECK (
        NOT acknowledged OR coalesce(carrier_state, '') IN ('ACCEPTED', 'EXECUTED'))
);

CREATE INDEX reconciliation_request_idx ON reconciliation (request_id, checked_at DESC);

-- When the operation was last checked and when the background checks it next (empty: never checked).
ALTER TABLE dispatch_operation
    ADD COLUMN reconciled_at     timestamptz,
    ADD COLUMN next_reconcile_at timestamptz,
    ADD CONSTRAINT dispatch_operation_reconcile_times_together
        CHECK ((reconciled_at IS NULL) = (next_reconcile_at IS NULL)),
    ADD CONSTRAINT dispatch_operation_reconcile_order
        CHECK (next_reconcile_at IS NULL OR reconciled_at IS NULL OR next_reconcile_at >= reconciled_at);

CREATE INDEX dispatch_operation_next_reconcile_idx ON dispatch_operation (next_reconcile_at);

-- Problems that reconciliation opens. The partial unique index problem_open_per_request_kind is defined by
-- inbox_id IS NULL, so it already gives both new kinds "one open per request".
ALTER TABLE problem
    ADD COLUMN reconciliation_id uuid,
    ADD CONSTRAINT problem_reconciliation_fk FOREIGN KEY (reconciliation_id) REFERENCES reconciliation (id),
    DROP CONSTRAINT problem_kind_valid,
    DROP CONSTRAINT problem_source_matches_kind,
    DROP CONSTRAINT problem_decision_matches_kind,
    ADD CONSTRAINT problem_kind_valid CHECK (kind IN ('DELIVERY_UNKNOWN', 'DELIVERY_FAILED', 'INVALID_MESSAGE',
                                                      'MESSAGE_FAILED', 'RESULT_CONFLICT', 'EXTERNAL_MISSING',
                                                      'RECONCILIATION_CONFLICT')),
    ADD CONSTRAINT problem_source_matches_kind CHECK (
        kind NOT IN ('DELIVERY_UNKNOWN', 'DELIVERY_FAILED', 'INVALID_MESSAGE', 'MESSAGE_FAILED', 'RESULT_CONFLICT',
                     'EXTERNAL_MISSING', 'RECONCILIATION_CONFLICT')
        OR (kind IN ('DELIVERY_UNKNOWN', 'DELIVERY_FAILED', 'EXTERNAL_MISSING')
            AND request_id IS NOT NULL AND inbox_id IS NULL AND reconciliation_id IS NULL)
        OR (kind IN ('INVALID_MESSAGE', 'MESSAGE_FAILED') AND inbox_id IS NOT NULL AND reconciliation_id IS NULL)
        OR (kind = 'RESULT_CONFLICT'
            AND inbox_id IS NOT NULL AND request_id IS NOT NULL AND reconciliation_id IS NULL)
        OR (kind = 'RECONCILIATION_CONFLICT'
            AND request_id IS NOT NULL AND reconciliation_id IS NOT NULL AND inbox_id IS NULL)),
    ADD CONSTRAINT problem_decision_matches_kind CHECK (
        decision IS NULL
        OR decision NOT IN ('SYSTEM', 'RETRIED', 'APPLY_CARRIER', 'KEEP_OURS', 'ACKNOWLEDGED')
        OR (decision = 'SYSTEM'
            AND kind IN ('DELIVERY_UNKNOWN', 'DELIVERY_FAILED', 'EXTERNAL_MISSING', 'RECONCILIATION_CONFLICT'))
        OR (decision = 'RETRIED' AND kind IN ('DELIVERY_FAILED', 'EXTERNAL_MISSING'))
        OR (decision = 'APPLY_CARRIER' AND kind IN ('RESULT_CONFLICT', 'RECONCILIATION_CONFLICT'))
        OR (decision = 'KEEP_OURS' AND kind IN ('RESULT_CONFLICT', 'RECONCILIATION_CONFLICT', 'EXTERNAL_MISSING'))
        OR (decision = 'ACKNOWLEDGED' AND kind IN ('INVALID_MESSAGE', 'MESSAGE_FAILED')));

-- A message id is unique per source: a result taken over by reconciliation must not collapse into a broken feed
-- record with the same messageId. A repeat across sources is caught by the result rules (STALE).
DROP INDEX inbox_message_id_unique;
CREATE UNIQUE INDEX inbox_source_message_id_unique ON inbox (source, message_id) WHERE message_id IS NOT NULL;
