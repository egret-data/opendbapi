-- Spec §14: token buckets shared by every instance when opendbapi.rate-limit.shared is on, so a
-- limit holds across pods instead of multiplying by their number. Not workspace data (keys carry it).
CREATE TABLE rate_bucket (
    key        text PRIMARY KEY,
    tokens     double precision NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX rate_bucket_updated_idx ON rate_bucket (updated_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON rate_bucket TO ${app_role};
