-- Whether the account is listed in the directory, as Mastodon's discoverable accounts are. Accounts opt in, so it starts
-- false. It's part of the profile because other servers learn it from the actor document, and list their own users by it.
ALTER TABLE profile
    ADD COLUMN discoverable BOOLEAN NOT NULL DEFAULT false;

-- Few accounts opt in, so the directory starts from them rather than from every profile.
CREATE INDEX profile_discoverable_idx ON profile (actor_id) WHERE discoverable;
