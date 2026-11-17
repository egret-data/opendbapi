-- Personal API tokens for automation such as CI (spec §8.6): the bearer acts as the user who
-- created the token, optionally only in one workspace. Only a hash of the token is kept.
CREATE TABLE api_token (
    id            uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          text NOT NULL,
    prefix        text NOT NULL,
    token_hash    text NOT NULL UNIQUE,
    workspace_id  uuid REFERENCES workspace (id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz,
    last_used_at  timestamptz,
    revoked_at    timestamptz,
    PRIMARY KEY (id)
);
CREATE INDEX api_token_user_idx ON api_token (user_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON api_token TO ${app_role};
