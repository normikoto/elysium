-- Muting a thread only keeps its notifications away from the account that muted it, so nothing here is federated. There's
-- no foreign key on the thread, since threads have no table of their own, and merging two threads moves their mutes.
CREATE TABLE status_thread_mute
(
    actor_id  UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    thread_id UUID NOT NULL,
    PRIMARY KEY (actor_id, thread_id)
);
CREATE INDEX status_thread_mute_thread_idx ON status_thread_mute (thread_id);
