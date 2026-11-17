-- Spec §12: column masks (per datasource column or per endpoint result column) and row
-- filters (per endpoint, from service account attributes).
CREATE TABLE data_policy (
    id            uuid NOT NULL DEFAULT gen_random_uuid(),
    workspace_id  uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    datasource_id uuid,
    endpoint_id   uuid,
    kind          text NOT NULL CHECK (kind IN ('mask', 'row_filter')),
    column_name   text NOT NULL,
    rule          jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CHECK ((datasource_id IS NULL) <> (endpoint_id IS NULL)),
    CHECK (kind = 'mask' OR endpoint_id IS NOT NULL),
    FOREIGN KEY (workspace_id, datasource_id) REFERENCES datasource (workspace_id, id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, endpoint_id) REFERENCES endpoint (workspace_id, id) ON DELETE CASCADE
);
CREATE INDEX data_policy_datasource_idx ON data_policy (datasource_id) WHERE datasource_id IS NOT NULL;
CREATE INDEX data_policy_endpoint_idx ON data_policy (endpoint_id) WHERE endpoint_id IS NOT NULL;
CREATE UNIQUE INDEX data_policy_one_row_filter ON data_policy (endpoint_id) WHERE kind = 'row_filter';

ALTER TABLE data_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON data_policy
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON data_policy TO ${app_role};
