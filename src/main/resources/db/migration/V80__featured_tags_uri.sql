-- The collection of hashtags a remote actor features, as Mastodon's featuredTags names it. Its items replace what
-- featured_tag holds for the actor, so featured_tag now holds remote actors' tags too, not only local accounts'.
ALTER TABLE actor
    ADD COLUMN featured_tags_uri TEXT;

-- Remote actors stored before this have no featured_tags_uri yet, and a copy under a day old isn't fetched again, so their
-- featured tags would read as none until it's stale. Making them stale has each fetched again when it's next used. The
-- day is taken off rather than set, since the remote actor with a username is chosen by the latest updated_at.
UPDATE actor
SET updated_at = updated_at - interval '1 day'
WHERE NOT is_local;
