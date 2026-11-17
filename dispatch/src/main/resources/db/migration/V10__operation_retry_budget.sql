-- The operation's retry budget and its applied record (docs/specs/operator-problems.md §2.2.1).

-- An operator's retry starts a new budget of retries: the attempt number the budget started from. The retry policy
-- counts attempts - retry_from; attempts itself only grows: it numbers exchange_attempt and fences the outcome.
ALTER TABLE dispatch_operation ADD COLUMN retry_from integer NOT NULL DEFAULT 0;
ALTER TABLE dispatch_operation ADD CONSTRAINT dispatch_operation_retry_from_range
    CHECK (retry_from BETWEEN 0 AND attempts);

-- The record whose content is applied to the request now: set by processing and by an operator's decision on a
-- conflict; results of the same sequence are compared with it.
ALTER TABLE dispatch_operation ADD COLUMN applied_inbox_id uuid;
ALTER TABLE dispatch_operation ADD CONSTRAINT dispatch_operation_applied_inbox_fk
    FOREIGN KEY (applied_inbox_id) REFERENCES inbox (id);

-- Existing operations: the applied record with the highest sequence.
UPDATE dispatch_operation o
   SET applied_inbox_id = (
       SELECT i.id FROM inbox i
        WHERE i.operation_id = o.operation_id AND i.processing_status = 'APPLIED'
        ORDER BY i.sequence DESC, i.received_at DESC, i.id
        LIMIT 1);
