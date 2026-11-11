-- The remote followers that a local followers-only status's Create was sent to, so that its Delete reaches them after they
-- unfollow or follow again. status_id has no foreign key, because the Delete is worked out after the status's row is gone.
-- Statuses from before this have no rows, and their activities still go to whoever has followed since before they were posted.
CREATE TABLE followers_only_recipient
(
    status_id UUID NOT NULL,
    author_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    actor_id  UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    PRIMARY KEY (status_id, actor_id)
);
CREATE INDEX followers_only_recipient_author_idx ON followers_only_recipient (author_id);
CREATE INDEX followers_only_recipient_actor_idx ON followers_only_recipient (actor_id);
