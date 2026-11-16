-- Dev roles. Keep in sync with backend/*/src/test/resources/db/init-roles.sql.
CREATE ROLE opendbapi_owner LOGIN PASSWORD 'owner';
CREATE ROLE opendbapi_app LOGIN PASSWORD 'app' NOBYPASSRLS;
ALTER DATABASE opendbapi OWNER TO opendbapi_owner;
