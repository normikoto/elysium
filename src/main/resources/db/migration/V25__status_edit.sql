-- The versions of a status that an edit replaced. The current version stays in status, so a status that was never edited
-- has no rows here. created_at is when the version became current: when the status was published, or last edited.
CREATE TABLE status_edit
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status_id    UUID        NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    text         TEXT        NOT NULL,
    content      TEXT        NOT NULL,
    spoiler_text TEXT        NOT NULL,
    language     TEXT,
    sensitive    BOOLEAN     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX status_edit_status_idx ON status_edit (status_id, created_at);
