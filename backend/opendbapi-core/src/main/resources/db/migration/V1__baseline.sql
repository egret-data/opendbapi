-- OpenDBAPI baseline schema (spec §4, P1 tables).
--
-- Isolation model (spec §4.0):
--   * Every workspace-owned table carries workspace_id and has row-level security FORCED,
--     so even the table owner is filtered.
--   * The application connects as ${app_role} (NOBYPASSRLS) and sets
--     opendbapi.workspace_id with set_config(..., true) at the start of each transaction.
--   * Cross-workspace references are impossible by construction: child tables reference
--     parents through composite (workspace_id, id) foreign keys.
--   * Tables read across workspaces (workspace, users, membership, identity providers,
--     audit_log) are not RLS-protected; the application filters them explicitly.

-- Current workspace for RLS policies; NULL when unset (NULLIF guards the '' value a
-- custom setting reverts to after its first SET LOCAL in a session).
CREATE FUNCTION current_workspace_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('opendbapi.workspace_id', true), '')::uuid $$;

-- ---------------------------------------------------------------------------
-- System-level tables
-- ---------------------------------------------------------------------------

CREATE TABLE workspace (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    name        text NOT NULL,
    status      text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
    quotas      jsonb NOT NULL DEFAULT '{}'::jsonb,
    archived_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identity_provider (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    type       text NOT NULL CHECK (type IN ('ldap', 'oidc')),
    name       text NOT NULL,
    enabled    boolean NOT NULL DEFAULT true,
    config     jsonb NOT NULL,
    secret_ref text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username          text NOT NULL,
    email             text,
    display_name      text,
    source            text NOT NULL CHECK (source IN ('local', 'ldap', 'oidc')),
    idp_id            uuid REFERENCES identity_provider (id),
    external_id       text,
    password_hash     text,
    system_admin      boolean NOT NULL DEFAULT false,
    disabled          boolean NOT NULL DEFAULT false,
    failed_logins     int NOT NULL DEFAULT 0,
    locked_until      timestamptz,
    last_login_at     timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CHECK ((source = 'local') = (idp_id IS NULL)),
    CHECK (source = 'local' OR password_hash IS NULL)
);
-- Local usernames are unique; external users are unique per provider.
CREATE UNIQUE INDEX users_local_username_uq ON users (lower(username)) WHERE source = 'local';
CREATE UNIQUE INDEX users_external_uq ON users (idp_id, external_id) WHERE source <> 'local';

CREATE TABLE workspace_member (
    workspace_id uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         text NOT NULL CHECK (role IN ('owner', 'admin', 'script_author', 'author', 'viewer')),
    -- true when the role came from group_role_mapping and is recomputed at login
    from_mapping boolean NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX workspace_member_user_idx ON workspace_member (user_id);

CREATE TABLE group_role_mapping (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idp_id         uuid NOT NULL REFERENCES identity_provider (id) ON DELETE CASCADE,
    external_group text NOT NULL,
    workspace_id   uuid REFERENCES workspace (id) ON DELETE CASCADE,
    -- NULL workspace + role 'system_admin' grants the system flag
    role           text NOT NULL CHECK (role IN ('system_admin', 'owner', 'admin', 'script_author', 'author', 'viewer')),
    managed        boolean NOT NULL DEFAULT false,
    CHECK ((role = 'system_admin') = (workspace_id IS NULL)),
    UNIQUE NULLS NOT DISTINCT (idp_id, external_group, workspace_id)
);

CREATE TABLE audit_log (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ts           timestamptz NOT NULL DEFAULT now(),
    workspace_id uuid REFERENCES workspace (id) ON DELETE SET NULL,
    user_id      uuid REFERENCES users (id) ON DELETE SET NULL,
    action       text NOT NULL,
    target_type  text,
    target_id    text,
    detail       jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX audit_log_ws_ts_idx ON audit_log (workspace_id, ts DESC);

-- ---------------------------------------------------------------------------
-- Workspace-owned tables (RLS)
-- ---------------------------------------------------------------------------

CREATE TABLE api_group (
    id           uuid NOT NULL DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name         text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (workspace_id, id),
    UNIQUE (workspace_id, name)
);

CREATE TABLE datasource (
    id           uuid NOT NULL DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name         text NOT NULL,
    description  text,
    type         text NOT NULL CHECK (type IN ('jdbc', 'elasticsearch', 'http')),
    config       jsonb NOT NULL,
    secret_ref   text,
    read_only    boolean NOT NULL DEFAULT false,
    created_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (workspace_id, id),
    UNIQUE (workspace_id, name)
);

CREATE TABLE endpoint (
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    workspace_id    uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    group_id        uuid,
    name            text NOT NULL,
    description     text,
    method          text NOT NULL CHECK (method IN ('GET', 'POST', 'PUT', 'DELETE')),
    path            text NOT NULL CHECK (path ~ '^[A-Za-z0-9_\-{}]+(/[A-Za-z0-9_\-{}]+)*$'),
    state           text NOT NULL DEFAULT 'draft' CHECK (state IN ('draft', 'live', 'disabled')),
    live_version_id uuid,
    access          text NOT NULL DEFAULT 'private' CHECK (access IN ('private', 'public')),
    definition      jsonb NOT NULL DEFAULT '{}'::jsonb,
    dirty           boolean NOT NULL DEFAULT true,
    deleted_at      timestamptz,
    created_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (workspace_id, id),
    FOREIGN KEY (workspace_id, group_id) REFERENCES api_group (workspace_id, id),
    -- spec §5.1: only draft or disabled endpoints may be trashed; live must have a version
    CHECK (deleted_at IS NULL OR state <> 'live'),
    CHECK (state <> 'live' OR live_version_id IS NOT NULL)
);
-- A trashed endpoint frees its route.
CREATE UNIQUE INDEX endpoint_route_uq ON endpoint (workspace_id, method, path) WHERE deleted_at IS NULL;

CREATE TABLE endpoint_version (
    id            uuid NOT NULL DEFAULT gen_random_uuid(),
    workspace_id  uuid NOT NULL,
    endpoint_id   uuid NOT NULL,
    version       int NOT NULL CHECK (version > 0),
    definition    jsonb NOT NULL,
    output_schema jsonb NOT NULL DEFAULT '[]'::jsonb,
    note          text,
    published_by  uuid REFERENCES users (id) ON DELETE SET NULL,
    published_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (workspace_id, id),
    UNIQUE (endpoint_id, version),
    FOREIGN KEY (workspace_id, endpoint_id) REFERENCES endpoint (workspace_id, id) ON DELETE CASCADE
);

ALTER TABLE endpoint
    ADD FOREIGN KEY (workspace_id, live_version_id) REFERENCES endpoint_version (workspace_id, id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE service_account (
    id           uuid NOT NULL DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name         text NOT NULL,
    description  text,
    attributes   jsonb NOT NULL DEFAULT '{}'::jsonb,
    disabled     boolean NOT NULL DEFAULT false,
    created_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (workspace_id, id),
    UNIQUE (workspace_id, name)
);

CREATE TABLE oauth_client (
    id                 uuid NOT NULL DEFAULT gen_random_uuid(),
    workspace_id       uuid NOT NULL,
    service_account_id uuid NOT NULL,
    client_id          text NOT NULL UNIQUE,
    secret_hash        text NOT NULL,
    hmac_required      boolean NOT NULL DEFAULT false,
    public_key         text,
    response_encrypt   boolean NOT NULL DEFAULT false,
    expires_at         timestamptz,
    last_used_at       timestamptz,
    revoked_at         timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    FOREIGN KEY (workspace_id, service_account_id) REFERENCES service_account (workspace_id, id) ON DELETE CASCADE,
    CHECK (NOT response_encrypt OR public_key IS NOT NULL)
);

CREATE TABLE endpoint_grant (
    workspace_id       uuid NOT NULL,
    service_account_id uuid NOT NULL,
    resource_type      text NOT NULL CHECK (resource_type IN ('endpoint', 'group')),
    resource_id        uuid NOT NULL,
    granted_by         uuid REFERENCES users (id) ON DELETE SET NULL,
    granted_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (service_account_id, resource_type, resource_id),
    FOREIGN KEY (workspace_id, service_account_id) REFERENCES service_account (workspace_id, id) ON DELETE CASCADE
);

-- Partitioned by month (spec §4.1); partitions are created ahead by a scheduled job,
-- the default partition catches anything outside them.
CREATE TABLE access_log (
    id                 uuid NOT NULL DEFAULT gen_random_uuid(),
    ts                 timestamptz NOT NULL,
    workspace_id       uuid NOT NULL,
    endpoint_id        uuid,
    version            int,
    method             text,
    path               text,
    status             int NOT NULL,
    duration_ms        int NOT NULL,
    rows               int,
    client_id          text,
    service_account_id uuid,
    ip                 inet,
    error_code         text,
    error              text,
    trace_id           text,
    -- non-null: the call came from Try-it by this user; excluded from statistics
    actor_user_id      uuid,
    PRIMARY KEY (id, ts)
) PARTITION BY RANGE (ts);
CREATE TABLE access_log_default PARTITION OF access_log DEFAULT;
CREATE INDEX access_log_ws_ts_idx ON access_log (workspace_id, ts DESC);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'api_group', 'datasource', 'endpoint', 'endpoint_version',
        'service_account', 'oauth_client', 'endpoint_grant', 'access_log'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY workspace_isolation ON %I
                 USING (workspace_id = current_workspace_id())
                 WITH CHECK (workspace_id = current_workspace_id())', t);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Privileges for the application role
-- ---------------------------------------------------------------------------

GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ${flyway:defaultSchema} TO ${app_role};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ${flyway:defaultSchema} TO ${app_role};
GRANT EXECUTE ON FUNCTION current_workspace_id() TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};
