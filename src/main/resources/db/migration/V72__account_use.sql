-- The UTC days on which each local account used the server, through a login session or an access token, for the active
-- account counts in NodeInfo and the instance API. A day per row answers both the weekly counts and the 30 and 180 day ones.
-- Rows older than those counts reach are removed by a recurring job.
CREATE TABLE account_use_day
(
    actor_id UUID NOT NULL REFERENCES local_account (actor_id) ON DELETE CASCADE,
    day      DATE NOT NULL,
    PRIMARY KEY (actor_id, day)
);
CREATE INDEX account_use_day_day_idx ON account_use_day (day);
