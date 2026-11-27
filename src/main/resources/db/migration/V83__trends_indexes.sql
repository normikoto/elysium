-- Trending links read one link's week of history at a time, and trending statuses start from the past two days' likes
-- and public boosts.
CREATE INDEX status_link_url_idx ON status_link (url, status_id);

CREATE INDEX status_like_activity_at_idx ON status_like (activity_at);

CREATE INDEX status_public_reblog_published_idx ON status (published_at)
    WHERE reblog_of_id IS NOT NULL AND visibility = 'public' AND NOT local_only;
