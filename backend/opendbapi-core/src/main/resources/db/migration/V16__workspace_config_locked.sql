-- Spec §15.2: a workspace managed by configuration files only. What the files describe can
-- then change through config:apply alone, never by editing in the UI.
ALTER TABLE workspace ADD COLUMN config_locked boolean NOT NULL DEFAULT false;
