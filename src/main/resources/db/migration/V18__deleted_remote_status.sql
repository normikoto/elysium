-- Remote statuses whose Delete has arrived, so that a Create that runs after it, since jobs have no order, can't store the
-- status again. These are by author, because only the author's server can speak for a status. The URIs are hashed in the
-- index because together they can be too long for one.
CREATE TABLE deleted_remote_status
(
    uri        TEXT        NOT NULL,
    actor_uri  TEXT        NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX deleted_remote_status_idx ON deleted_remote_status (md5(uri), md5(actor_uri));
CREATE INDEX deleted_remote_status_deleted_at_idx ON deleted_remote_status (deleted_at);
