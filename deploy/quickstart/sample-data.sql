-- A small shop to try OpenDBAPI with:
--   docker compose -f deploy/quickstart/docker-compose.yml exec -T postgres psql -U postgres -d opendbapi < deploy/quickstart/sample-data.sql
CREATE SCHEMA IF NOT EXISTS shop;

CREATE TABLE IF NOT EXISTS shop.customers (
    id      int PRIMARY KEY,
    name    text NOT NULL,
    email   text NOT NULL,
    country text NOT NULL,
    tier    text NOT NULL
);

CREATE TABLE IF NOT EXISTS shop.orders (
    id          int PRIMARY KEY,
    customer_id int NOT NULL REFERENCES shop.customers (id),
    placed_at   timestamptz NOT NULL,
    status      text NOT NULL,
    total       numeric(10, 2) NOT NULL
);

INSERT INTO shop.customers VALUES
    (1, 'Ada Lovelace', 'ada@example.com', 'GB', 'gold'),
    (2, 'Grace Hopper', 'grace@example.com', 'US', 'gold'),
    (3, 'Linh Nguyen', 'linh@example.com', 'VN', 'silver'),
    (4, 'Kenji Sato', 'kenji@example.com', 'JP', 'silver'),
    (5, 'Amara Okafor', 'amara@example.com', 'NG', 'bronze'),
    (6, 'Lucas Martin', 'lucas@example.com', 'FR', 'bronze')
ON CONFLICT DO NOTHING;

INSERT INTO shop.orders
SELECT g, 1 + (g % 6), timestamptz '2026-10-01' + (g || ' hours')::interval,
       (ARRAY['paid', 'shipped', 'delivered', 'refunded'])[1 + g % 4], round((20 + (g * 37) % 480)::numeric, 2)
FROM generate_series(1, 120) AS g
ON CONFLICT DO NOTHING;

-- A read-only login for the datasource.
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'shop_reader') THEN
        CREATE ROLE shop_reader LOGIN PASSWORD 'shop-reader';
    END IF;
END $$;
GRANT USAGE ON SCHEMA shop TO shop_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA shop TO shop_reader;
