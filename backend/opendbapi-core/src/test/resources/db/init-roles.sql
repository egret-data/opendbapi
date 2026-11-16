-- Mirrors deploy/dev/postgres/init-roles.sql: owner runs migrations, app is subject to RLS.
CREATE ROLE opendbapi_owner LOGIN PASSWORD 'owner';
CREATE ROLE opendbapi_app LOGIN PASSWORD 'app' NOBYPASSRLS;
ALTER DATABASE opendbapi OWNER TO opendbapi_owner;
