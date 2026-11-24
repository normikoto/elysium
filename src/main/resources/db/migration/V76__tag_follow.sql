-- A local account's follow of a hashtag, by its normalized name. There's no table of tags, as status_tag explains, so any
-- valid name can be followed, even one that nothing uses yet. Tag follows are never federated. IDs are time-ordered, so
-- that followed_tags pages by them, as Mastodon's cursors expect.
CREATE TABLE tag_follow
(
    id         UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    UNIQUE (account_id, name)
);
CREATE INDEX tag_follow_page_idx ON tag_follow (account_id, id);
