-- When the copies of an account on another server's avatar and header were kept, so that old ones can be removed after
-- elysium.attachments.remote-cache-retention, as attachments' copies are. Copies from before this count from now, so that
-- upgrading doesn't remove them all at once.
ALTER TABLE profile
    ADD COLUMN avatar_cached_at TIMESTAMPTZ,
    ADD COLUMN header_cached_at TIMESTAMPTZ;

UPDATE profile SET avatar_cached_at = now() WHERE note_html IS NOT NULL AND avatar IS NOT NULL;
UPDATE profile SET header_cached_at = now() WHERE note_html IS NOT NULL AND header IS NOT NULL;

CREATE INDEX profile_avatar_cached_idx ON profile (avatar_cached_at) WHERE note_html IS NOT NULL AND avatar IS NOT NULL;
CREATE INDEX profile_header_cached_idx ON profile (header_cached_at) WHERE note_html IS NOT NULL AND header IS NOT NULL;
