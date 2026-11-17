-- Spec §13.2: webhooks that hear about failing endpoints. The URL is a secret (it alone
-- authorises posting to the chat room), stored as an enc:v1: or env: reference.
CREATE TABLE alert_channel (
    id            uuid NOT NULL DEFAULT gen_random_uuid(),
    workspace_id  uuid NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name          text NOT NULL,
    kind          text NOT NULL CHECK (kind IN ('slack', 'teams', 'google_chat', 'webhook')),
    url_ref       text NOT NULL,
    url_host      text NOT NULL,
    enabled       boolean NOT NULL DEFAULT true,
    last_sent_at  timestamptz,
    last_error    text,
    created_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (workspace_id, name)
);

ALTER TABLE alert_channel ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_channel FORCE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON alert_channel
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON alert_channel TO ${app_role};
