ALTER TABLE profile ADD COLUMN remote_links_queued_at TIMESTAMPTZ;

-- Earlier verification could have checked a different URL than the field displayed.
UPDATE profile_field
SET verified_at = NULL
FROM actor
WHERE actor.id = profile_field.actor_id AND NOT actor.is_local AND profile_field.verified_at IS NOT NULL;
