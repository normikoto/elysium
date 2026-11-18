-- Whether an account that approves its followers by hand approves requests from accounts it follows straight away. It's
-- on the profile, beside manually_approves_followers, so that the lock a request takes on that setting covers this one too.
-- It isn't part of the actor document, so other servers never learn it.
ALTER TABLE profile
    ADD COLUMN approves_followed BOOLEAN NOT NULL DEFAULT false;
