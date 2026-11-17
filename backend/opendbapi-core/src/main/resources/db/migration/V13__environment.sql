-- Spec §15.2: an environment maps the logical datasource names used in configuration files
-- to the datasources of this workspace, so one file applies to dev, staging and prod.
CREATE TABLE environment (
    id            uuid NOT NULL DEFAULT gen_random_uuid(),
    workspace_id  uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name          text NOT NULL,
    bindings      jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (workspace_id, name)
);

ALTER TABLE environment ENABLE ROW LEVEL SECURITY;
ALTER TABLE environment FORCE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON environment
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON environment TO ${app_role};
