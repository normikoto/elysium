-- Reports to this server's admins, about an account and some of its statuses. A report from another server is stored with
-- whoever signed its Flag as the reporter, usually that server's instance actor, and activity_uri keeps a repeat of it
-- from being stored twice. A Flag about several accounts here makes one report for each.
CREATE TABLE report
(
    id           UUID PRIMARY KEY,
    reporter_id  UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    target_id    UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    comment      TEXT        NOT NULL DEFAULT '',
    category     TEXT        NOT NULL CHECK (category IN ('spam', 'legal', 'other')),
    -- Whether a Flag was sent to the target's server. Only ever true for a remote target.
    forwarded    BOOLEAN     NOT NULL DEFAULT FALSE,
    activity_uri TEXT,
    assigned_to  UUID REFERENCES actor (id) ON DELETE SET NULL,
    resolved_by  UUID REFERENCES actor (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    resolved_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX report_activity_idx ON report (md5(activity_uri), target_id) WHERE activity_uri IS NOT NULL;
CREATE INDEX report_open_idx ON report (id DESC) WHERE resolved_at IS NULL;
CREATE INDEX report_reporter_idx ON report (reporter_id);
CREATE INDEX report_target_idx ON report (target_id);

-- A status that its author deletes, or an admin removes, goes from the reports it's in.
CREATE TABLE report_status
(
    report_id UUID NOT NULL REFERENCES report (id) ON DELETE CASCADE,
    status_id UUID NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    PRIMARY KEY (report_id, status_id)
);
CREATE INDEX report_status_status_idx ON report_status (status_id);

-- An activity with no actor is signed as the instance actor, which isn't in actor, so that nobody here is named as sending
-- it, as with a forwarded report.
ALTER TABLE outbound_activity ALTER COLUMN actor_id DROP NOT NULL;

ALTER TABLE inbox_notification DROP CONSTRAINT inbox_notification_type_check;
ALTER TABLE inbox_notification ADD CONSTRAINT inbox_notification_type_check
    CHECK (type IN ('mention', 'follow', 'follow_request', 'favourite', 'reblog', 'quote', 'quoted_update',
                    'admin.report'));
ALTER TABLE inbox_notification ADD COLUMN report_id UUID REFERENCES report (id) ON DELETE CASCADE;
CREATE INDEX inbox_notification_report_idx ON inbox_notification (report_id);
