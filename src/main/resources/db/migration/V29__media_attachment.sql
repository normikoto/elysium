-- Files attached to statuses, or uploaded to be. A row with no status is an upload waiting to be posted, or one kept for a
-- while after its status was deleted, so that it can be posted again. It's removed after removable_after, and at once if
-- that's null. Deleting a status leaves its attachments' rows with no status rather than removing them, so that the
-- files they name are always found and removed.
CREATE TABLE media_attachment
(
    id              UUID PRIMARY KEY,
    account_id      UUID        NOT NULL REFERENCES actor (id),
    status_id       UUID REFERENCES status (id) ON DELETE SET NULL,
    position        SMALLINT    NOT NULL DEFAULT 0,
    type            TEXT        NOT NULL CHECK (type IN ('image', 'video', 'audio', 'unknown')),
    -- The files in the media directory. A remote attachment has none until it's downloaded, and none again once its copy
    -- is pruned.
    file_name       TEXT,
    preview_name    TEXT,
    content_type    TEXT,
    file_size       BIGINT,
    width           INTEGER,
    height          INTEGER,
    preview_width   INTEGER,
    preview_height  INTEGER,
    description     TEXT,
    focus_x         DOUBLE PRECISION,
    focus_y         DOUBLE PRECISION,
    blurhash        TEXT,
    -- Where another server has the file, for a remote attachment.
    remote_url      TEXT,
    cached_at       TIMESTAMPTZ,
    -- A remote attachment that an admin's filter keeps from being downloaded.
    rejected        BOOLEAN     NOT NULL DEFAULT false,
    removable_after TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (preview_name IS NULL OR file_name IS NOT NULL OR remote_url IS NOT NULL),
    CHECK (file_name IS NOT NULL OR remote_url IS NOT NULL)
);

CREATE INDEX media_attachment_status_idx ON media_attachment (status_id, position) WHERE status_id IS NOT NULL;
CREATE INDEX media_attachment_unattached_idx ON media_attachment (account_id, removable_after) WHERE status_id IS NULL;
CREATE INDEX media_attachment_account_idx ON media_attachment (account_id);

-- Files that nothing refers to any more, and that are still to be removed from the media directory. A name is added in the
-- same statement as its reference is removed, and taken off once the file is gone, so that a file is never removed while
-- something refers to it, and is still removed if the server stops in between.
CREATE TABLE media_file_deletion
(
    name      TEXT PRIMARY KEY,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
