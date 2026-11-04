-- db-scheduler's tables, from its PostgreSQL scripts. Don't change these without checking the scripts for the version in use.
CREATE TABLE scheduled_tasks
(
    task_name            TEXT                     NOT NULL,
    task_instance        TEXT                     NOT NULL,
    task_data            BYTEA,
    execution_time       TIMESTAMP WITH TIME ZONE NOT NULL,
    picked               BOOLEAN                  NOT NULL,
    picked_by            TEXT,
    last_success         TIMESTAMP WITH TIME ZONE,
    last_failure         TIMESTAMP WITH TIME ZONE,
    consecutive_failures INT,
    last_heartbeat       TIMESTAMP WITH TIME ZONE,
    version              BIGINT                   NOT NULL,
    priority             SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);
CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);

-- db-scheduler-ui's history.
CREATE TABLE scheduled_execution_logs
(
    id                   BIGINT                   NOT NULL PRIMARY KEY,
    task_name            TEXT                     NOT NULL,
    task_instance        TEXT                     NOT NULL,
    task_data            BYTEA,
    picked_by            TEXT,
    time_started         TIMESTAMP WITH TIME ZONE NOT NULL,
    time_finished        TIMESTAMP WITH TIME ZONE NOT NULL,
    succeeded            BOOLEAN                  NOT NULL,
    duration_ms          BIGINT                   NOT NULL,
    exception_class      TEXT,
    exception_message    TEXT,
    exception_stacktrace TEXT
);
CREATE INDEX stl_started_idx ON scheduled_execution_logs (time_started);
CREATE INDEX stl_task_name_idx ON scheduled_execution_logs (task_name);
CREATE INDEX stl_exception_class_idx ON scheduled_execution_logs (exception_class);

-- Activities are kept here rather than in job data, so that admins looking at jobs don't see private posts, and each
-- inbox's job doesn't need its own copy. The body is text because signatures cover its exact bytes.
CREATE TABLE outbound_activity
(
    activity_id TEXT PRIMARY KEY,
    actor_id    UUID                     NOT NULL,
    body        TEXT                     NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX outbound_activity_created_at_idx ON outbound_activity (created_at);

-- The primary key is also how repeats of an activity are spotted.
CREATE TABLE inbound_activity
(
    activity_id TEXT PRIMARY KEY,
    actor_uri   TEXT                     NOT NULL,
    type        TEXT                     NOT NULL,
    body        TEXT                     NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX inbound_activity_received_at_idx ON inbound_activity (received_at);

-- Stored so that a restart doesn't start sending to a failing server again, and so that every node backs off together.
CREATE TABLE federation_host
(
    host                 TEXT PRIMARY KEY,
    consecutive_failures INT                      NOT NULL,
    retry_after          TIMESTAMP WITH TIME ZONE NOT NULL
);
