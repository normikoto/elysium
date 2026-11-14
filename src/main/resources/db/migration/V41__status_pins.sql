-- activity_uri is the Add's ID, for local pins only, since remote servers don't give their Adds one.
CREATE TABLE status_pin
(
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    status_id UUID NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    pinned_at TIMESTAMPTZ NOT NULL,
    activity_uri TEXT,
    UNIQUE (actor_id, status_id)
);
CREATE INDEX status_pin_actor_idx ON status_pin (actor_id, pinned_at DESC);

-- A Remove can run before the Add it follows, since jobs have no order, and the Add mustn't pin the status again.
CREATE TABLE removed_pin
(
    actor_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    status_uri TEXT NOT NULL,
    removed_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX removed_pin_idx ON removed_pin (actor_id, md5(status_uri));
CREATE INDEX removed_pin_time_idx ON removed_pin (removed_at);

-- A remote actor's featured collection, which its Adds and Removes have to target. It's null for local actors, and for
-- remote ones whose documents don't have a usable one. Existing rows fill in when the actor is next fetched.
ALTER TABLE actor
    ADD COLUMN featured_uri TEXT;
