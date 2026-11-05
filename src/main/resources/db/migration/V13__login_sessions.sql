-- These tables are Spring Session's, from its schema-postgresql.sql. They're created here because Spring Boot only creates them
-- itself for embedded databases. session_id holds a hash, as LoginSessionConfig explains, so it's TEXT rather than CHAR(36). It
-- has to be TEXT and not a longer CHAR, because comparing a CHAR with the hash, which is TEXT, couldn't use the index.
CREATE TABLE spring_session
(
    primary_id            CHAR(36) NOT NULL,
    session_id            TEXT     NOT NULL,
    creation_time         BIGINT   NOT NULL,
    last_access_time      BIGINT   NOT NULL,
    max_inactive_interval INT      NOT NULL,
    expiry_time           BIGINT   NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes
(
    session_primary_id CHAR(36)     NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- Goes up each time a password is replaced, so that a login that was checking the old password at that moment can be told apart
-- from one that checked the new password. See LoginGenerationFilter.
ALTER TABLE account_credential
    ADD COLUMN password_generation BIGINT NOT NULL DEFAULT 0;
