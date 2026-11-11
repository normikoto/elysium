-- For listing and revoking one user's apps. The consent table's primary key starts with the client, so it can't serve a
-- lookup by user.
CREATE INDEX oauth2_authorization_principal_idx ON oauth2_authorization (principal_name, registered_client_id);
CREATE INDEX oauth2_authorization_consent_principal_idx ON oauth2_authorization_consent (principal_name);
