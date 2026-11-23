-- A local account's choice to have its old statuses and boosts deleted, as Mastodon's automated post deletion does. An
-- account without a row has it off. min_favourites and min_boosts are null when they keep nothing. last_run_at is when
-- the job last looked at the account, so that it takes accounts in turn when there are more than one run can reach.
-- scanned_to is the last status the job looked at, so that each run goes on from there rather than read every old status
-- the account keeps again. It's null to start from the oldest, which it does again once it reaches posts too new to delete.
CREATE TABLE post_cleanup_policy
(
    actor_id             UUID PRIMARY KEY REFERENCES actor (id) ON DELETE CASCADE,
    enabled              BOOLEAN     NOT NULL,
    min_age_days         INTEGER     NOT NULL CHECK (min_age_days IN (7, 14, 30, 60, 90, 180, 365, 730)),
    keep_pinned          BOOLEAN     NOT NULL,
    keep_direct          BOOLEAN     NOT NULL,
    keep_polls           BOOLEAN     NOT NULL,
    keep_media           BOOLEAN     NOT NULL,
    keep_self_favourited BOOLEAN     NOT NULL,
    keep_self_bookmarked BOOLEAN     NOT NULL,
    min_favourites       INTEGER CHECK (min_favourites > 0),
    min_boosts           INTEGER CHECK (min_boosts > 0),
    last_run_at          TIMESTAMPTZ,
    scanned_to           UUID
);

CREATE INDEX post_cleanup_policy_enabled_idx ON post_cleanup_policy (last_run_at NULLS FIRST) WHERE enabled;
