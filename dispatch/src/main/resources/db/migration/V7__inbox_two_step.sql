-- Results are received first and processed one by one afterwards (docs/specs/feed-bad-records.md):
-- receiving stores the raw record and moves the feed cursor; processing decides each record in its own transaction,
-- retrying a temporary failure a few times before giving up (FAILED).

-- The raw record is kept verbatim as text: jsonb refuses some records (e.g. "\u0000"), and receiving must store any.
ALTER TABLE inbox ALTER COLUMN payload TYPE text USING payload::text;

ALTER TABLE inbox ADD COLUMN attempts integer;
UPDATE inbox SET attempts = 0;
ALTER TABLE inbox ALTER COLUMN attempts SET NOT NULL;
ALTER TABLE inbox ALTER COLUMN attempts SET DEFAULT 0;

-- When a RECEIVED record is due for (another) processing attempt; empty once it is decided.
ALTER TABLE inbox ADD COLUMN next_attempt_at timestamptz;
-- Last temporary processing error (the outcome's own explanation stays in reason).
ALTER TABLE inbox ADD COLUMN last_error text;

ALTER TABLE inbox DROP CONSTRAINT inbox_processing_status_valid;
ALTER TABLE inbox ADD CONSTRAINT inbox_processing_status_valid
    CHECK (processing_status IN ('RECEIVED', 'APPLIED', 'STALE', 'CONFLICT', 'INVALID', 'FAILED'));
ALTER TABLE inbox ADD CONSTRAINT inbox_attempts_non_negative CHECK (attempts >= 0);
ALTER TABLE inbox ADD CONSTRAINT inbox_received_has_next_attempt
    CHECK ((processing_status = 'RECEIVED') = (next_attempt_at IS NOT NULL));
ALTER TABLE inbox ADD CONSTRAINT inbox_feed_has_feed_seq CHECK (source <> 'FEED' OR feed_seq IS NOT NULL);

-- A feed position is received once; the cursor ensures it, the database enforces it.
CREATE UNIQUE INDEX inbox_feed_seq_unique ON inbox (feed_seq) WHERE source = 'FEED';
-- The processing queue.
CREATE INDEX inbox_due_idx ON inbox (next_attempt_at) WHERE processing_status = 'RECEIVED';
