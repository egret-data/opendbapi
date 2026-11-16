-- Callers quote the request id of a failed call (spec §9.1); authors look it up on the Activity page.
ALTER TABLE access_log ADD COLUMN request_id text;
CREATE INDEX access_log_request_idx ON access_log (workspace_id, request_id);
