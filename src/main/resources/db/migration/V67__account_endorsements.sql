-- A local account's endorsement of an account it follows, which Mastodon's API calls an account pin. It references the
-- follow, as list members do, so that whatever removes a follow, such as an unfollow, a Reject, a block, a move, or
-- cleaning up after an account or a server, removes the endorsement too, as on Mastodon. Endorsements are never federated.
CREATE TABLE account_endorsement
(
    id         UUID PRIMARY KEY,
    account_id UUID        NOT NULL,
    target_id  UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (account_id, target_id) REFERENCES follow (follower_id, followed_id) ON DELETE CASCADE,
    UNIQUE (account_id, target_id)
);
CREATE INDEX account_endorsement_page_idx ON account_endorsement (account_id, created_at, id);
