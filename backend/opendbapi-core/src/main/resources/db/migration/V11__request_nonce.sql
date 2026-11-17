-- Spec §8.2: nonces of signed requests, shared by every instance so a request accepted by one
-- pod cannot be replayed to another. Client ids are globally unique, so no row-level security.
CREATE TABLE request_nonce (
    client_id  text NOT NULL,
    nonce      text NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (client_id, nonce)
);
CREATE INDEX request_nonce_expires_idx ON request_nonce (expires_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON request_nonce TO ${app_role};
