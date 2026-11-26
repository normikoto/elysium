-- The collection of hashtags a remote actor features, as Mastodon's featuredTags names it. Its items replace what
-- featured_tag holds for the actor, so featured_tag now holds remote actors' tags too, not only local accounts'.
ALTER TABLE actor
    ADD COLUMN featured_tags_uri TEXT;
