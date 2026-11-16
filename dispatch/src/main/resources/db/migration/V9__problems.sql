-- Problems of the exchange that need an operator (docs/specs/operator-problems.md §2.2): a delivery that is unknown
-- or failed, a feed record that is invalid, failed or conflicting. A problem is opened and closed in the same
-- transaction as the transition that causes it; the operator's decision is kept here.

CREATE TABLE problem (
    id          uuid          PRIMARY KEY,
    kind        text          NOT NULL,
    status      text          NOT NULL,
    -- Empty only for a record of no known request (its service is unknown; every operator sees it).
    request_id  uuid          REFERENCES delivery_request (id),
    -- The source record of the message kinds.
    inbox_id    uuid          REFERENCES inbox (id),
    -- Why it was opened: the operation's last_error or the record's reason.
    details     text          NOT NULL,
    opened_at   timestamptz   NOT NULL,
    resolved_at timestamptz,
    -- Empty when the system closed it.
    resolved_by uuid          REFERENCES app_user (id),
    decision    text,
    comment     varchar(2000),

    CONSTRAINT problem_kind_valid CHECK (kind IN ('DELIVERY_UNKNOWN', 'DELIVERY_FAILED', 'INVALID_MESSAGE',
                                                  'MESSAGE_FAILED', 'RESULT_CONFLICT')),
    CONSTRAINT problem_status_valid CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT problem_decision_valid CHECK (decision IN ('SYSTEM', 'RETRIED', 'APPLY_CARRIER', 'KEEP_OURS',
                                                          'ACKNOWLEDGED')),
    -- Each rule in its own constraint and blind to the others' values, so a violation names the rule broken.
    CONSTRAINT problem_resolution_complete CHECK (
        (status <> 'OPEN'
            OR (resolved_at IS NULL AND decision IS NULL AND resolved_by IS NULL AND comment IS NULL))
        AND (status <> 'RESOLVED' OR (resolved_at IS NOT NULL AND decision IS NOT NULL))),
    CONSTRAINT problem_system_has_no_author CHECK (decision IS NULL OR (decision = 'SYSTEM') = (resolved_by IS NULL)),
    CONSTRAINT problem_decision_needs_comment CHECK (
        decision IS NULL OR decision NOT IN ('APPLY_CARRIER', 'KEEP_OURS', 'ACKNOWLEDGED') OR comment IS NOT NULL),
    CONSTRAINT problem_comment_not_blank CHECK (comment IS NULL OR btrim(comment) <> ''),
    CONSTRAINT problem_source_matches_kind CHECK (
        kind NOT IN ('DELIVERY_UNKNOWN', 'DELIVERY_FAILED', 'INVALID_MESSAGE', 'MESSAGE_FAILED', 'RESULT_CONFLICT')
        OR (kind IN ('DELIVERY_UNKNOWN', 'DELIVERY_FAILED') AND request_id IS NOT NULL AND inbox_id IS NULL)
        OR (kind IN ('INVALID_MESSAGE', 'MESSAGE_FAILED') AND inbox_id IS NOT NULL)
        OR (kind = 'RESULT_CONFLICT' AND inbox_id IS NOT NULL AND request_id IS NOT NULL)),
    CONSTRAINT problem_decision_matches_kind CHECK (
        decision IS NULL
        OR decision NOT IN ('SYSTEM', 'RETRIED', 'APPLY_CARRIER', 'KEEP_OURS', 'ACKNOWLEDGED')
        OR (decision = 'SYSTEM' AND kind IN ('DELIVERY_UNKNOWN', 'DELIVERY_FAILED'))
        OR (decision = 'RETRIED' AND kind = 'DELIVERY_FAILED')
        OR (decision IN ('APPLY_CARRIER', 'KEEP_OURS') AND kind = 'RESULT_CONFLICT')
        OR (decision = 'ACKNOWLEDGED' AND kind IN ('INVALID_MESSAGE', 'MESSAGE_FAILED')))
);

-- A request has at most one open delivery problem of each kind.
CREATE UNIQUE INDEX problem_open_per_request_kind ON problem (request_id, kind)
    WHERE status = 'OPEN' AND inbox_id IS NULL;
-- A record gives one problem in its whole life.
CREATE UNIQUE INDEX problem_per_inbox_record ON problem (inbox_id) WHERE inbox_id IS NOT NULL;
CREATE INDEX problem_list_idx ON problem (status, opened_at DESC);
CREATE INDEX problem_request_idx ON problem (request_id);

-- Carry over the state that already needs an operator: a new table does not fill itself. Ids come from
-- PostgreSQL's own UUIDv7, the opening time is the migration's.
INSERT INTO problem (id, kind, status, request_id, details, opened_at)
SELECT uuidv7(), CASE delivery_status WHEN 'UNKNOWN' THEN 'DELIVERY_UNKNOWN' ELSE 'DELIVERY_FAILED' END, 'OPEN',
       request_id, coalesce(last_error, 'Delivery is ' || delivery_status), now()
  FROM dispatch_operation
 WHERE delivery_status IN ('UNKNOWN', 'FAILED');

INSERT INTO problem (id, kind, status, request_id, inbox_id, details, opened_at)
SELECT uuidv7(),
       CASE i.processing_status
           WHEN 'INVALID' THEN 'INVALID_MESSAGE'
           WHEN 'FAILED' THEN 'MESSAGE_FAILED'
           ELSE 'RESULT_CONFLICT' END,
       'OPEN', o.request_id, i.id, coalesce(i.reason, 'Record is ' || i.processing_status), now()
  FROM inbox i
  LEFT JOIN dispatch_operation o ON o.operation_id = i.operation_id
 WHERE i.processing_status IN ('INVALID', 'FAILED')
    OR (i.processing_status = 'CONFLICT' AND o.request_id IS NOT NULL);
