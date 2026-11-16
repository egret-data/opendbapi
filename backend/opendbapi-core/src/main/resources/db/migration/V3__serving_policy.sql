-- Spec §14: per-workspace firewall and caller rate limits, edited by workspace admins.
ALTER TABLE workspace ADD COLUMN serving_policy jsonb NOT NULL DEFAULT '{}'::jsonb;
