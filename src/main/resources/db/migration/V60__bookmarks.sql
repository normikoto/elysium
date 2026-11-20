-- Bookmarks are private, and only local accounts make them, so nothing here is federated.
CREATE TABLE status_bookmark
(
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    status_id UUID NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    UNIQUE (actor_id, status_id)
);
CREATE INDEX status_bookmark_page_idx ON status_bookmark (actor_id, id DESC);
CREATE INDEX status_bookmark_status_idx ON status_bookmark (status_id);
