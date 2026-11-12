-- Results received from the carrier (feed or reconciliation) and the feed read position.

CREATE TABLE inbox (
    id                uuid        PRIMARY KEY,
    -- Invalid records may lack a message id, so uniqueness applies only when it is present.
    message_id        text,
    feed_seq          bigint,
    -- No FK: a record may reference an unknown operation (then it is INVALID).
    operation_id      uuid,
    sequence          integer,
    result_status     text,
    payload           jsonb       NOT NULL,
    source            text        NOT NULL,

    processing_status text        NOT NULL,
    reason            text,

    -- A repeated message does not create a new row; it increments this counter.
    duplicate_count   integer     NOT NULL DEFAULT 0,
    last_duplicate_at timestamptz,

    received_at       timestamptz NOT NULL,

    CONSTRAINT inbox_source_valid CHECK (source IN ('FEED', 'RECONCILIATION')),
    CONSTRAINT inbox_processing_status_valid
        CHECK (processing_status IN ('APPLIED', 'STALE', 'CONFLICT', 'INVALID')),
    CONSTRAINT inbox_duplicate_count_non_negative CHECK (duplicate_count >= 0)
);

CREATE UNIQUE INDEX inbox_message_id_unique ON inbox (message_id) WHERE message_id IS NOT NULL;
CREATE INDEX inbox_operation_idx ON inbox (operation_id, received_at);

CREATE TABLE feed_cursor (
    name          text        PRIMARY KEY,
    last_feed_seq bigint      NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT feed_cursor_seq_non_negative CHECK (last_feed_seq >= 0)
);

INSERT INTO feed_cursor (name, last_feed_seq, updated_at) VALUES ('carrier', 0, now());
