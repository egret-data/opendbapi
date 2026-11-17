-- Spec §16: major versions. A new major copies an endpoint to a new route; copies share a
-- lineage and run side by side. An endpoint can be deprecated, with an optional sunset date,
-- which its responses announce (Deprecation, Sunset, Link rel="successor-version").
ALTER TABLE endpoint
    ADD COLUMN lineage_id    uuid,
    ADD COLUMN major         int NOT NULL DEFAULT 1 CHECK (major >= 1),
    ADD COLUMN deprecated_at timestamptz,
    ADD COLUMN sunset_at     timestamptz;

-- endpoint has FORCE ROW LEVEL SECURITY, which applies to its owner too: without a workspace in
-- the session this UPDATE would see no rows. Lift it for the backfill only.
ALTER TABLE endpoint NO FORCE ROW LEVEL SECURITY;
UPDATE endpoint SET lineage_id = id;
ALTER TABLE endpoint FORCE ROW LEVEL SECURITY;
ALTER TABLE endpoint ALTER COLUMN lineage_id SET NOT NULL;
CREATE INDEX endpoint_lineage_idx ON endpoint (workspace_id, lineage_id);

-- A new endpoint starts its own lineage unless it was created as a major of another.
CREATE FUNCTION endpoint_default_lineage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.lineage_id := coalesce(NEW.lineage_id, NEW.id);
    RETURN NEW;
END $$;
CREATE TRIGGER endpoint_default_lineage BEFORE INSERT ON endpoint
    FOR EACH ROW EXECUTE FUNCTION endpoint_default_lineage();
