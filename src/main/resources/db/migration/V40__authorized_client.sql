-- Clients that have ever been authorized, so that the ones that never were can be removed. Registration needs no login, so
-- those would otherwise build up. This can't be worked out from Spring's tables, because revoking an app removes its rows
-- there, and a revoked app has to keep working when someone authorizes it again.
CREATE TABLE authorized_client
(
    registered_client_id VARCHAR(100) PRIMARY KEY REFERENCES oauth2_registered_client (id) ON DELETE CASCADE
);

INSERT INTO authorized_client (registered_client_id)
SELECT id
FROM oauth2_registered_client c
WHERE EXISTS (SELECT 1
              FROM oauth2_authorization a
              WHERE a.registered_client_id = c.id
                AND a.access_token_value IS NOT NULL)
   OR EXISTS (SELECT 1 FROM oauth2_authorization_consent a WHERE a.registered_client_id = c.id);

-- Removing a client removes what Spring stored for it, which has no foreign key to find it by.
CREATE INDEX oauth2_authorization_client_idx ON oauth2_authorization (registered_client_id);
