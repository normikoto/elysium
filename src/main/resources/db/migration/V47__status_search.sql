-- Whether the account's public statuses can be found by anyone's search, as Mastodon's indexable accounts' can. Without it,
-- only the statuses someone wrote, favourited, or was mentioned in turn up in their search. It's part of the profile
-- because other servers learn it from the actor document.
ALTER TABLE profile
    ADD COLUMN indexable BOOLEAN NOT NULL DEFAULT false;

-- Full-text search over statuses. The 'simple' configuration doesn't stem, so it treats every language alike, which a
-- configuration chosen from each status's language can't do in an index. Boosts have no text of their own. Queries have to
-- repeat this expression exactly, as StatusRepository.SEARCH_VECTOR does, for the index to be used.
CREATE INDEX status_search_idx ON status USING gin (to_tsvector('simple', spoiler_text || ' ' || text))
    WHERE reblog_of_id IS NULL;
