-- Spec §8.2: a client that must sign requests gets its own HMAC key, sealed with the master
-- key (the client secret itself is only stored as a hash, so it cannot key an HMAC).
ALTER TABLE oauth_client ADD COLUMN hmac_key_ref text;
ALTER TABLE oauth_client ADD CONSTRAINT oauth_client_hmac_key CHECK (NOT hmac_required OR hmac_key_ref IS NOT NULL);
