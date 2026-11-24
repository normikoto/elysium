-- The server's VAPID key pair, which push services check to tell its messages apart from anyone else's. Clients are given
-- the public key when they subscribe, so changing it breaks every subscription. The primary key only allows one row.
CREATE TABLE push_server_key
(
    singleton   BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    -- An uncompressed P-256 point, as clients are given it
    public_key  BYTEA       NOT NULL,
    -- PKCS #8
    private_key BYTEA       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One per access token, as in Mastodon. Deleting the token's grant deletes it, which covers revoking an app, changing a
-- password, and deleting an account. A token revoked through /oauth/revoke keeps its grant, so that's checked on sending.
CREATE TABLE push_subscription
(
    id               UUID PRIMARY KEY,
    actor_id         UUID         NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    authorization_id VARCHAR(100) NOT NULL UNIQUE REFERENCES oauth2_authorization (id) ON DELETE CASCADE,
    endpoint         TEXT         NOT NULL,
    p256dh           BYTEA        NOT NULL,
    auth_secret      BYTEA        NOT NULL,
    -- Whether it uses RFC 8291's aes128gcm, rather than the older aesgcm draft
    standard         BOOLEAN      NOT NULL,
    -- The notification types to send
    alerts           TEXT[]       NOT NULL,
    policy           TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX push_subscription_actor_idx ON push_subscription (actor_id);
