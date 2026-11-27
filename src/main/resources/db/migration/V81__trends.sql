CREATE INDEX status_public_original_published_idx ON status (published_at DESC, id DESC)
    WHERE visibility = 'public' AND NOT local_only AND reblog_of_id IS NULL;

CREATE TABLE status_link
(
    status_id UUID NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    url       TEXT NOT NULL,
    PRIMARY KEY (status_id, url)
);
