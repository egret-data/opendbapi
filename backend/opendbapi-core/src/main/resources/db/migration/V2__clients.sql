-- Spec §8.1: callers present only a client_id at the token endpoint, before any workspace is
-- known. This directory maps client ids to workspaces without row-level security; it holds
-- no secret. Rows disappear with their client (the foreign key check ignores RLS).
CREATE TABLE oauth_client_directory (
    client_id    text PRIMARY KEY REFERENCES oauth_client (client_id) ON DELETE CASCADE,
    workspace_id uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE
);

-- RS256 keys that sign access tokens. The private key is sealed with the master key
-- (enc:v1:) or, without one, never stored (single-instance development only).
CREATE TABLE signing_key (
    kid         text PRIMARY KEY,
    private_key text NOT NULL,
    public_key  text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    retired_at  timestamptz
);

GRANT SELECT, INSERT, UPDATE, DELETE ON oauth_client_directory, signing_key TO ${app_role};
