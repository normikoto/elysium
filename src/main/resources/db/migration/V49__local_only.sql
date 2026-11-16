-- Whether a local status stays on this server. Every query and listener that could hand a status to another server, or to
-- someone not logged in, has to check it, since visibility alone says an open status can go anywhere.
ALTER TABLE status
    ADD COLUMN local_only BOOLEAN NOT NULL DEFAULT false;
