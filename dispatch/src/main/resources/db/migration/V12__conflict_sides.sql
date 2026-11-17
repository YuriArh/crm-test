-- Our side of a discrepancy is kept as it was when it was found (docs/specs/conflict-history.md §2.2): a check row
-- keeps our result as fields next to the carrier's, and a problem with two sides keeps a snapshot of our result at
-- the moment it was opened. The request's result now is not history: after an operator's decision it is the other
-- side.

-- Our result at the check. local_result stays the comparison key of the "decided earlier" rule; it is built from
-- these fields (ResultMessage.content: status|tracking|price|reason), so it parses back without loss: '|' can only
-- be in the reason, and the reason is last.
ALTER TABLE reconciliation
    ADD COLUMN local_status          text,
    ADD COLUMN local_tracking_number text,
    ADD COLUMN local_price           numeric(12, 2),
    ADD COLUMN local_reason          text;

UPDATE reconciliation
   SET local_status          = split_part(local_result, '|', 1),
       local_tracking_number = nullif(split_part(local_result, '|', 2), ''),
       local_price           = nullif(split_part(local_result, '|', 3), '')::numeric,
       local_reason          = nullif(substring(local_result FROM '^[^|]*\|[^|]*\|[^|]*\|(.*)$'), '')
 WHERE local_result IS NOT NULL;

-- Each rule in its own constraint and blind to the others' values, so a violation names the rule broken.
ALTER TABLE reconciliation
    ADD CONSTRAINT reconciliation_local_status_valid CHECK (local_status IN ('SUCCEEDED', 'REJECTED')),
    ADD CONSTRAINT reconciliation_local_result_complete CHECK (
        CASE
            WHEN local_status IS NULL THEN
                local_tracking_number IS NULL AND local_price IS NULL AND local_reason IS NULL
            WHEN local_status = 'SUCCEEDED' THEN local_tracking_number IS NOT NULL AND local_price IS NOT NULL
            WHEN local_status = 'REJECTED' THEN local_reason IS NOT NULL
            ELSE true
        END),
    ADD CONSTRAINT reconciliation_local_key_matches CHECK ((local_result IS NULL) = (local_status IS NULL));

-- Our result when the problem was opened. our_recorded = false: the problem has no two sides, or it is older than
-- this migration and its snapshot was never taken. our_recorded = true with empty fields: we had no result then.
-- The default only fills the existing rows: every new problem states it.
ALTER TABLE problem
    ADD COLUMN our_recorded        boolean NOT NULL DEFAULT false,
    ADD COLUMN our_status          text,
    ADD COLUMN our_tracking_number text,
    ADD COLUMN our_price           numeric(12, 2),
    ADD COLUMN our_reason          text;

ALTER TABLE problem ALTER COLUMN our_recorded DROP DEFAULT;

-- A reconciliation conflict shows its check row, which already held both sides of the same moment. Other old
-- problems keep no snapshot: today's result of the request may be the carrier's after an operator's decision.
UPDATE problem p
   SET our_recorded        = true,
       our_status          = r.local_status,
       our_tracking_number = r.local_tracking_number,
       our_price           = r.local_price,
       our_reason          = r.local_reason
  FROM reconciliation r
 WHERE r.id = p.reconciliation_id
   AND p.kind = 'RECONCILIATION_CONFLICT';

ALTER TABLE problem
    ADD CONSTRAINT problem_our_status_valid CHECK (our_status IN ('SUCCEEDED', 'REJECTED')),
    ADD CONSTRAINT problem_our_result_complete CHECK (
        CASE
            WHEN our_status IS NULL THEN our_tracking_number IS NULL AND our_price IS NULL AND our_reason IS NULL
            WHEN our_status = 'SUCCEEDED' THEN our_tracking_number IS NOT NULL AND our_price IS NOT NULL
            WHEN our_status = 'REJECTED' THEN our_reason IS NOT NULL
            ELSE true
        END),
    ADD CONSTRAINT problem_our_side_needs_record CHECK (
        our_recorded OR (our_status IS NULL AND our_tracking_number IS NULL AND our_price IS NULL
                         AND our_reason IS NULL)),
    ADD CONSTRAINT problem_our_side_matches_kind CHECK (
        NOT our_recorded OR kind IN ('RESULT_CONFLICT', 'RECONCILIATION_CONFLICT', 'EXTERNAL_MISSING'));
