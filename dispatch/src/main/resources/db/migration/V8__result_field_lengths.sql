-- Result fields of a request are bounded in the database too (docs/specs/feed-bad-records.md §6-7, NF03).
-- The contract is stricter for the tracking number (TRK-XXXXXXXX); the database keeps a general bound.
-- The price is already bounded by numeric(12, 2).
ALTER TABLE delivery_request ADD CONSTRAINT delivery_request_tracking_number_length
    CHECK (char_length(tracking_number) <= 64);
ALTER TABLE delivery_request ADD CONSTRAINT delivery_request_rejection_reason_length
    CHECK (char_length(rejection_reason) <= 1000);
