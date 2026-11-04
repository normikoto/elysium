-- What's left of a deleted status that anyone could fetch, so that other servers can be told it's gone rather than unknown.
CREATE TABLE status_tombstone
(
    status_id  UUID PRIMARY KEY,
    uri        TEXT        NOT NULL,
    actor_id   UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    deleted_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX status_tombstone_actor_idx ON status_tombstone (actor_id);
