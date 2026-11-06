-- A remote actor's followers collection, so that an activity addressed to it can be matched with the local accounts that
-- follow that actor. It's null for local actors, whose collections are derived from their URIs, and for remote ones whose
-- documents don't have a usable one. Existing rows fill in when the actor is next fetched.
ALTER TABLE actor
    ADD COLUMN followers_uri TEXT;
