-- The secret is set when someone starts setting up two-factor login, and only counts once totp_enabled_at is set by
-- confirming a code. totp_last_step is the time step of the last code accepted, so that no code works twice.
ALTER TABLE account_credential
    ADD COLUMN totp_secret     BYTEA,
    ADD COLUMN totp_enabled_at TIMESTAMPTZ,
    ADD COLUMN totp_last_step  BIGINT;

-- Single-use codes for logging in without the authenticator app. Only hashes are stored.
CREATE TABLE account_recovery_code
(
    actor_id  UUID NOT NULL REFERENCES account_credential (actor_id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL,
    PRIMARY KEY (actor_id, code_hash)
);
