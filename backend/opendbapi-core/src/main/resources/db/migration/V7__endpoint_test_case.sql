-- Spec §11.3: saved test cases of an endpoint, run against its draft.
CREATE TABLE endpoint_test_case (
    id                 uuid NOT NULL DEFAULT gen_random_uuid(),
    workspace_id       uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    endpoint_id        uuid NOT NULL,
    name               text NOT NULL,
    service_account_id uuid,
    params             jsonb NOT NULL DEFAULT '{}'::jsonb,
    expect             jsonb NOT NULL DEFAULT '{}'::jsonb,
    last_result        jsonb,
    created_by         uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (endpoint_id, name),
    FOREIGN KEY (workspace_id, endpoint_id) REFERENCES endpoint (workspace_id, id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, service_account_id) REFERENCES service_account (workspace_id, id) ON DELETE SET NULL (service_account_id)
);
CREATE INDEX endpoint_test_case_endpoint_idx ON endpoint_test_case (endpoint_id);

ALTER TABLE endpoint_test_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE endpoint_test_case FORCE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON endpoint_test_case
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON endpoint_test_case TO ${app_role};
