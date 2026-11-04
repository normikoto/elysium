CREATE TABLE follow
(
    id           UUID PRIMARY KEY,
    follower_id  UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    followed_id  UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    state        TEXT        NOT NULL CHECK (state IN ('pending', 'accepted')),
    activity_uri TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (follower_id, followed_id)
);
CREATE INDEX follow_followed_idx ON follow (followed_id);

-- Follow activities that have been undone. Jobs have no order, so an Undo can run before its Follow, and a Follow's job can
-- run again after its Undo. Either way, the Follow mustn't then add the follower back.
CREATE TABLE undone_follow
(
    activity_uri TEXT PRIMARY KEY,
    undone_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX undone_follow_undone_at_idx ON undone_follow (undone_at);

-- Other servers' actor documents don't have to include these.
ALTER TABLE actor
    ALTER COLUMN outbox_uri DROP NOT NULL,
    ALTER COLUMN public_key_pem DROP NOT NULL;
