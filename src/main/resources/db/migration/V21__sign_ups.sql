-- Someone who has asked for an account and hasn't got one yet, because they haven't confirmed their email address, or an
-- admin hasn't approved them. They have no actor until then, so nothing else on the server can see them.
CREATE TABLE pending_sign_up
(
    id                      UUID PRIMARY KEY,
    username                TEXT        NOT NULL,
    email                   TEXT        NOT NULL,
    password_hash           TEXT        NOT NULL,
    reason                  TEXT,
    -- Null once the address is confirmed, so that the link stops working.
    verification_token_hash TEXT UNIQUE,
    verified_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((verification_token_hash IS NULL) = (verified_at IS NOT NULL))
);

CREATE UNIQUE INDEX pending_sign_up_username ON pending_sign_up (lower(username));
CREATE UNIQUE INDEX pending_sign_up_email ON pending_sign_up (lower(email));

-- Null for accounts made with create-user or an invite, which never gave one.
ALTER TABLE account_credential
    ADD COLUMN email TEXT;

CREATE UNIQUE INDEX account_credential_email ON account_credential (lower(email));

CREATE TABLE invite
(
    id         UUID PRIMARY KEY,
    -- Only a hash, so that reading the database doesn't give anyone a working invite.
    code_hash  TEXT        NOT NULL UNIQUE,
    max_uses   INTEGER CHECK (max_uses > 0),
    uses       INTEGER     NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
