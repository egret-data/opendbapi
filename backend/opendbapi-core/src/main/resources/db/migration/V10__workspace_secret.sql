-- Secrets managed by OpenDBAPI (spec §4.5): named per workspace, the value sealed with the
-- master key and never returned. Datasource passwords and webhook URLs refer to them as
-- secret:NAME, so a value changes in one place.
CREATE TABLE workspace_secret (
    id            uuid NOT NULL DEFAULT gen_random_uuid(),
    workspace_id  uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name          text NOT NULL,
    description   text,
    value_ref     text NOT NULL,
    created_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (workspace_id, name)
);

ALTER TABLE workspace_secret ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_secret FORCE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON workspace_secret
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON workspace_secret TO ${app_role};
