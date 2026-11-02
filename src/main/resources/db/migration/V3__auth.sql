-- Kept apart from local_account so that the auth module never reads actor private keys.
CREATE TABLE account_credential
(
    actor_id      UUID PRIMARY KEY REFERENCES local_account (actor_id) ON DELETE CASCADE,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The tables below are Spring Authorization Server's, with its recommended PostgreSQL changes: blob becomes TEXT and
-- timestamp becomes TIMESTAMPTZ. Columns holding client-supplied lists are also TEXT, because registration is public
-- and is limited by validation rather than by column size.
CREATE TABLE oauth2_registered_client
(
    id                            VARCHAR(100) PRIMARY KEY,
    client_id                     VARCHAR(100) NOT NULL UNIQUE,
    client_id_issued_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_secret                 VARCHAR(200),
    client_secret_expires_at      TIMESTAMPTZ,
    client_name                   TEXT         NOT NULL,
    client_authentication_methods TEXT         NOT NULL,
    authorization_grant_types     TEXT         NOT NULL,
    redirect_uris                 TEXT,
    post_logout_redirect_uris     TEXT,
    scopes                        TEXT         NOT NULL,
    client_settings               TEXT         NOT NULL,
    token_settings                TEXT         NOT NULL
);

CREATE TABLE oauth2_authorization
(
    id                            VARCHAR(100) PRIMARY KEY,
    registered_client_id          VARCHAR(100) NOT NULL,
    principal_name                VARCHAR(200) NOT NULL,
    authorization_grant_type      VARCHAR(100) NOT NULL,
    authorized_scopes             TEXT,
    attributes                    TEXT,
    state                         VARCHAR(500),
    authorization_code_value      TEXT,
    authorization_code_issued_at  TIMESTAMPTZ,
    authorization_code_expires_at TIMESTAMPTZ,
    authorization_code_metadata   TEXT,
    access_token_value            TEXT,
    access_token_issued_at        TIMESTAMPTZ,
    access_token_expires_at       TIMESTAMPTZ,
    access_token_metadata         TEXT,
    access_token_type             VARCHAR(100),
    access_token_scopes           TEXT,
    oidc_id_token_value           TEXT,
    oidc_id_token_issued_at       TIMESTAMPTZ,
    oidc_id_token_expires_at      TIMESTAMPTZ,
    oidc_id_token_metadata        TEXT,
    refresh_token_value           TEXT,
    refresh_token_issued_at       TIMESTAMPTZ,
    refresh_token_expires_at      TIMESTAMPTZ,
    refresh_token_metadata        TEXT,
    user_code_value               TEXT,
    user_code_issued_at           TIMESTAMPTZ,
    user_code_expires_at          TIMESTAMPTZ,
    user_code_metadata            TEXT,
    device_code_value             TEXT,
    device_code_issued_at         TIMESTAMPTZ,
    device_code_expires_at        TIMESTAMPTZ,
    device_code_metadata          TEXT
);

-- Revoking a token looks it up across all seven of these columns with OR, which needs an index on each to avoid a scan.
CREATE INDEX oauth2_authorization_state_idx ON oauth2_authorization (state);
CREATE INDEX oauth2_authorization_code_idx ON oauth2_authorization (authorization_code_value);
CREATE INDEX oauth2_authorization_access_token_idx ON oauth2_authorization (access_token_value);
CREATE INDEX oauth2_authorization_id_token_idx ON oauth2_authorization (oidc_id_token_value);
CREATE INDEX oauth2_authorization_refresh_token_idx ON oauth2_authorization (refresh_token_value);
CREATE INDEX oauth2_authorization_user_code_idx ON oauth2_authorization (user_code_value);
CREATE INDEX oauth2_authorization_device_code_idx ON oauth2_authorization (device_code_value);

CREATE TABLE oauth2_authorization_consent
(
    registered_client_id VARCHAR(100) NOT NULL,
    principal_name       VARCHAR(200) NOT NULL,
    authorities          TEXT         NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
