CREATE TABLE actor
(
    id               UUID PRIMARY KEY,
    uri              TEXT        NOT NULL UNIQUE,
    username         TEXT        NOT NULL,
    domain           TEXT        NOT NULL,
    inbox_uri        TEXT        NOT NULL,
    outbox_uri       TEXT        NOT NULL,
    shared_inbox_uri TEXT,
    public_key_pem   TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX actor_username_domain_idx ON actor (lower(username), domain);

-- A row here is what makes an actor local.
CREATE TABLE local_account
(
    actor_id        UUID PRIMARY KEY REFERENCES actor (id) ON DELETE CASCADE,
    private_key_pem TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
