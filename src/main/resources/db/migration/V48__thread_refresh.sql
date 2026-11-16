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

-- For finding whether any job of a group is queued, by the prefix of its ID, which Jobs.isGroupQueued does for each check on
-- a thread being fetched. The primary key can't, since its collation doesn't sort by bytes.
CREATE INDEX scheduled_tasks_instance_idx ON scheduled_tasks (task_name, task_instance COLLATE "C");
