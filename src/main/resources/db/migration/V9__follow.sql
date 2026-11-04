-- activity_at is when the activity behind this follow arrived, because jobs have no order and only that can say which of two
-- activities is newer.
CREATE TABLE follow
(
    id           UUID PRIMARY KEY,
    follower_id  UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    followed_id  UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    state        TEXT        NOT NULL CHECK (state IN ('pending', 'accepted')),
    activity_uri TEXT,
    activity_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (follower_id, followed_id)
);
CREATE INDEX follow_followed_idx ON follow (followed_id);

-- Follow activities whose Undo got here first, so that the Follow can't add the follower when it arrives. These are by
-- follower, so that one actor can't affect another's. The URIs are hashed in the index because together they can be too long
-- for one.
CREATE TABLE undone_follow
(
    activity_uri TEXT        NOT NULL,
    follower_uri TEXT        NOT NULL,
    undone_at    TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX undone_follow_idx ON undone_follow (md5(activity_uri), md5(follower_uri));
CREATE INDEX undone_follow_undone_at_idx ON undone_follow (undone_at);

-- What an Undo covers: every Follow between these two actors that arrived up to undone_at. Jobs have no order, so one of those
-- can run after the Undo, and mustn't then add the follower back.
CREATE TABLE undone_follow_pair
(
    follower_uri TEXT        NOT NULL,
    followed_id  UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    undone_at    TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX undone_follow_pair_idx ON undone_follow_pair (md5(follower_uri), followed_id);
CREATE INDEX undone_follow_pair_undone_at_idx ON undone_follow_pair (undone_at);

-- A repeat of an activity from a server that uses the same ID for new requests. Only the two times can tell that apart from an
-- activity whose Undo got here first.
ALTER TABLE inbound_activity
    ADD COLUMN first_received_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE inbound_activity SET first_received_at = received_at;

-- Other servers' actor documents don't have to include these.
ALTER TABLE actor
    ALTER COLUMN outbox_uri DROP NOT NULL,
    ALTER COLUMN public_key_pem DROP NOT NULL;

-- Usernames only have to be unique for local accounts. A remote actor's identity is its URI, and its server can give a username
-- to a new actor while this server has an old copy of the last one to have it. An index can't look at local_account, so this is
-- kept here too. Every actor so far is local.
ALTER TABLE actor
    ADD COLUMN is_local BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE actor SET is_local = TRUE WHERE id IN (SELECT actor_id FROM local_account);
DROP INDEX actor_username_domain_idx;
CREATE UNIQUE INDEX actor_local_username_domain_idx ON actor (lower(username), domain) WHERE is_local;
