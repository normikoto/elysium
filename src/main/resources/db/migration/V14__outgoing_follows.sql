-- Whether an account's followers and following collections only say how many there are, and not who.
ALTER TABLE local_account
    ADD COLUMN hide_collections BOOLEAN NOT NULL DEFAULT FALSE;

-- Collections and lists of follows are paged newest first, by when each follow started. The unique index on
-- (follower_id, followed_id) can't serve this for following, and follow_followed_idx can't sort.
DROP INDEX follow_followed_idx;
CREATE INDEX follow_followed_created_idx ON follow (followed_id, created_at DESC, id DESC);
CREATE INDEX follow_follower_created_idx ON follow (follower_id, created_at DESC, id DESC);
