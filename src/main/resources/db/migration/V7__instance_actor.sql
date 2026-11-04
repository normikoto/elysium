-- The actor for the server itself, which signs requests that no user is behind. It isn't in actor or local_account,
-- because a row in local_account is what counts as a user. The primary key only allows one row.
CREATE TABLE instance_actor
(
    singleton       BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    public_key_pem  TEXT        NOT NULL,
    private_key_pem TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
