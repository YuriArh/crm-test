-- Fault modes (docs/specs/carrier-faults.md §4).

-- Fault modes remembered by an operation when it is first received.
ALTER TABLE operation ADD COLUMN fault_duplicate boolean;
ALTER TABLE operation ADD COLUMN fault_delayed boolean;
UPDATE operation SET fault_duplicate = false, fault_delayed = false;
ALTER TABLE operation ALTER COLUMN fault_duplicate SET NOT NULL;
ALTER TABLE operation ALTER COLUMN fault_delayed SET NOT NULL;

-- Message ids of the operation's feed entries, assigned at execution, so the result shown by
-- GET /operations and the one published to the feed (now or later) are the same message.
ALTER TABLE operation ADD COLUMN in_progress_message_id uuid;
ALTER TABLE operation ADD COLUMN result_message_id uuid;
UPDATE operation o
   SET in_progress_message_id = (SELECT f.message_id FROM feed_entry f
                                  WHERE f.operation_id = o.operation_id AND f.sequence = 1
                                  ORDER BY f.feed_seq LIMIT 1),
       result_message_id = (SELECT f.message_id FROM feed_entry f
                             WHERE f.operation_id = o.operation_id AND f.sequence = 2
                             ORDER BY f.feed_seq LIMIT 1)
 WHERE o.state = 'EXECUTED';

-- When the delayed feed entries are due; NULL — nothing is waiting.
ALTER TABLE operation ADD COLUMN result_publish_at timestamptz;

-- execution_count always equalled "1 if EXECUTED, else 0": it is derived from state now.
-- At most one execution is guaranteed by the primary key and the conditional ACCEPTED -> EXECUTED update.
ALTER TABLE operation DROP CONSTRAINT operation_execution_at_most_once;
ALTER TABLE operation DROP CONSTRAINT operation_accepted_not_executed;
ALTER TABLE operation DROP CONSTRAINT operation_executed_has_result;
ALTER TABLE operation DROP COLUMN execution_count;

ALTER TABLE operation ADD CONSTRAINT operation_accepted_not_executed CHECK (
    state <> 'ACCEPTED' OR (result_status IS NULL AND executed_at IS NULL));
ALTER TABLE operation ADD CONSTRAINT operation_executed_has_result CHECK (
    state <> 'EXECUTED' OR (result_status IS NOT NULL AND executed_at IS NOT NULL));
ALTER TABLE operation ADD CONSTRAINT operation_executed_has_message_ids CHECK (
    state <> 'EXECUTED' OR (in_progress_message_id IS NOT NULL AND result_message_id IS NOT NULL));
ALTER TABLE operation ADD CONSTRAINT operation_publish_at_only_delayed CHECK (
    result_publish_at IS NULL OR (fault_delayed AND state = 'EXECUTED'));

-- Publication queue of delayed results.
CREATE INDEX operation_result_publish_at_idx ON operation (result_publish_at) WHERE result_publish_at IS NOT NULL;
