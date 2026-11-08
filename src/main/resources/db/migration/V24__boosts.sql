-- A boost is a status of its own, so that it takes its place in timelines by its ID, as statuses do.
ALTER TABLE status ADD COLUMN reblog_of_id UUID REFERENCES status (id) ON DELETE CASCADE;
-- When a remote boost's Announce first arrived, which decides which of two Announces of the same post is current.
ALTER TABLE status ADD COLUMN activity_at TIMESTAMPTZ;
CREATE UNIQUE INDEX status_reblog_pair_idx ON status (actor_id, reblog_of_id) WHERE reblog_of_id IS NOT NULL;
CREATE INDEX status_reblog_idx ON status (reblog_of_id, id DESC) WHERE reblog_of_id IS NOT NULL;

ALTER TABLE inbox_notification DROP CONSTRAINT inbox_notification_type_check;
ALTER TABLE inbox_notification ADD CONSTRAINT inbox_notification_type_check
    CHECK (type IN ('mention', 'follow', 'follow_request', 'favourite', 'reblog'));
ALTER TABLE inbox_notification ADD COLUMN reblog_id UUID REFERENCES status (id) ON DELETE CASCADE;
CREATE INDEX inbox_notification_reblog_idx ON inbox_notification (reblog_id);
