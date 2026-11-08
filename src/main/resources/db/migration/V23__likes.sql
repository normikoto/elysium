CREATE TABLE status_like
(
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    status_id UUID NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    activity_uri TEXT NOT NULL,
    activity_at TIMESTAMPTZ NOT NULL,
    UNIQUE (actor_id, status_id)
);
CREATE INDEX status_like_page_idx ON status_like (status_id, id DESC);
CREATE INDEX status_like_activity_idx ON status_like (md5(activity_uri));

-- An Undo can run before its Like, including before the actor has been fetched.
CREATE TABLE undone_like
(
    activity_uri TEXT NOT NULL,
    actor_uri TEXT NOT NULL,
    undone_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX undone_like_idx ON undone_like (md5(activity_uri), md5(actor_uri));
CREATE INDEX undone_like_time_idx ON undone_like (undone_at);
CREATE TABLE undone_like_pair
(
    actor_uri TEXT NOT NULL,
    status_id UUID NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    undone_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX undone_like_pair_idx ON undone_like_pair (md5(actor_uri), status_id);
CREATE INDEX undone_like_pair_time_idx ON undone_like_pair (undone_at);

ALTER TABLE inbox_notification DROP CONSTRAINT inbox_notification_type_check;
ALTER TABLE inbox_notification ADD CONSTRAINT inbox_notification_type_check
    CHECK (type IN ('mention', 'follow', 'follow_request', 'favourite'));
ALTER TABLE inbox_notification ADD COLUMN like_id UUID REFERENCES status_like (id) ON DELETE CASCADE;
CREATE INDEX inbox_notification_like_idx ON inbox_notification (like_id);
