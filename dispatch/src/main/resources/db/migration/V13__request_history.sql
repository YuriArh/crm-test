-- History of a request (docs/specs/request-history.md §2.2). The request row is the current state; request_event
-- keeps its changes. Every change of a request makes a new version and writes exactly one event with that version,
-- in the same transaction. The exchange stays in its own tables and is not copied here.

CREATE TABLE request_event (
    id          uuid        PRIMARY KEY,
    request_id  uuid        NOT NULL REFERENCES delivery_request (id),
    -- The version of the request the change made; empty for a retry of the delivery, which does not change the
    -- request.
    version     bigint,
    occurred_at timestamptz NOT NULL,
    -- Empty: the system.
    actor_id    uuid        REFERENCES app_user (id),
    kind        text        NOT NULL,
    -- [{field, from, to}]: the fields that changed (a retry: the delivery status, always).
    changes     jsonb       NOT NULL,
    -- The carrier's record whose result was applied, and the problem an operator applied it in.
    inbox_id    uuid        REFERENCES inbox (id),
    problem_id  uuid        REFERENCES problem (id),

    CONSTRAINT request_event_kind_valid CHECK (kind IN ('EDITED', 'RESULT_CHANGED', 'DELIVERY_RETRIED')),
    CONSTRAINT request_event_version_matches_kind CHECK ((version IS NULL) = (kind = 'DELIVERY_RETRIED')),
    CONSTRAINT request_event_version_positive CHECK (version > 0),
    CONSTRAINT request_event_changes_array
        CHECK (jsonb_typeof(changes) = 'array' AND jsonb_array_length(changes) > 0),
    CONSTRAINT request_event_actor_required CHECK (kind = 'RESULT_CHANGED' OR actor_id IS NOT NULL),
    CONSTRAINT request_event_result_has_record CHECK (
        CASE kind
            WHEN 'RESULT_CHANGED' THEN inbox_id IS NOT NULL
            ELSE inbox_id IS NULL AND problem_id IS NULL
        END)
);

CREATE UNIQUE INDEX request_event_request_version_unique ON request_event (request_id, version)
    WHERE version IS NOT NULL;
CREATE INDEX request_event_request_idx ON request_event (request_id, occurred_at);

-- When a record got its processing outcome. Older rows stay empty: their real time is unknown, and the time of
-- receipt would pass for it.
ALTER TABLE inbox ADD COLUMN processed_at timestamptz;
ALTER TABLE inbox ADD CONSTRAINT inbox_received_not_processed
    CHECK (processing_status <> 'RECEIVED' OR processed_at IS NULL);
