-- Whether requests to follow this account wait for its approval, as Mastodon's locked accounts do. It's part of the profile
-- because other servers learn it from the actor document, which an Update of the profile sends again.
ALTER TABLE profile
    ADD COLUMN manually_approves_followers BOOLEAN NOT NULL DEFAULT false;
