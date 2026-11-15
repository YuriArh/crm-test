-- Open send attempts are closed by their own age (docs/dispatch-design.md §5, "attempt lifetime"):
-- the recovery pass looks for them every second, and there are only a few at any time.
CREATE INDEX exchange_attempt_open_idx ON exchange_attempt (started_at) WHERE finished_at IS NULL;
