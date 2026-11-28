-- Where the job that removes other servers' old statuses has got to. scanned_to is the last status it looked at, so that a
-- run goes on from there rather than reading every status it keeps again, and is null to start again from the oldest.
-- pass_started_at is when it last did, since a status kept before, such as one unbookmarked since, is only found by
-- going back over them.
CREATE TABLE remote_status_expiry
(
    singleton       BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    scanned_to      UUID,
    pass_started_at TIMESTAMPTZ
);
INSERT INTO remote_status_expiry DEFAULT VALUES;
