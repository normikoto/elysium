-- Statuses that an admin removed, and who did. For a remote status this is also what keeps it from being stored again, so
-- unlike deleted_remote_status, rows aren't purged with old jobs: its server never says it's gone, and can send it again
-- at any time. They go with the author's account, but not with a domain block, which can be lifted. removed_by is null
-- once that admin's actor is gone.
CREATE TABLE status_removal
(
    status_uri TEXT        NOT NULL,
    author_id  UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    author_uri TEXT        NOT NULL,
    local      BOOLEAN     NOT NULL,
    removed_by UUID REFERENCES actor (id) ON DELETE SET NULL,
    removed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX status_removal_idx ON status_removal (md5(status_uri), md5(author_uri));
CREATE INDEX status_removal_author_idx ON status_removal (author_id);

-- For finding a remote status by the page its server gave, which admins are likelier to have than its URI.
CREATE INDEX status_url_idx ON status (md5(url)) WHERE url IS NOT NULL;
