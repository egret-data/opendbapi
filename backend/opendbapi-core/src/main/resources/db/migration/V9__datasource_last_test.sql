-- Result of the last connection test of a saved datasource, shown in the list (spec §17.4).
-- Cleared when the connection settings change.
ALTER TABLE datasource ADD COLUMN last_test_at timestamptz;
ALTER TABLE datasource ADD COLUMN last_test_ok boolean;
ALTER TABLE datasource ADD COLUMN last_test_message text;
