-- When each status's thread was last fetched from other servers, so that it's fetched at most every so often whichever node
-- is asked, and how many statuses that stored, for clients checking on it. A row goes with its status, and the job cleanup
-- removes it once it's old.
CREATE TABLE thread_refresh
(
    status_id    UUID PRIMARY KEY REFERENCES status (id) ON DELETE CASCADE,
    started_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    result_count INT                      NOT NULL DEFAULT 0
);
CREATE INDEX thread_refresh_started_at_idx ON thread_refresh (started_at);
