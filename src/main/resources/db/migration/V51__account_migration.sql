-- What a local account chose, or what an account on another server's actor document said when it was last fetched.
-- moved_to_uri is a URI rather than a reference, since an account on another server can name one that isn't stored.
ALTER TABLE profile
    ADD COLUMN also_known_as TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN moved_to_uri  TEXT;

-- Each move that was acted on. activity_uri is the Move that was received, or null for a local account's own move.
CREATE TABLE account_move
(
    id           UUID PRIMARY KEY,
    actor_id     UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    target_id    UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    activity_uri TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX account_move_activity_idx ON account_move (md5(activity_uri)) WHERE activity_uri IS NOT NULL;
CREATE INDEX account_move_actor_idx ON account_move (actor_id, created_at);
CREATE INDEX account_move_target_idx ON account_move (target_id);

ALTER TABLE inbox_notification ADD COLUMN target_id UUID REFERENCES actor (id) ON DELETE CASCADE;
CREATE INDEX inbox_notification_target_idx ON inbox_notification (target_id) WHERE target_id IS NOT NULL;
ALTER TABLE inbox_notification DROP CONSTRAINT inbox_notification_type_check;
ALTER TABLE inbox_notification ADD CONSTRAINT inbox_notification_type_check
    CHECK (type IN ('mention', 'follow', 'follow_request', 'favourite', 'reblog', 'quote', 'quoted_update',
                    'admin.report', 'poll', 'pleroma:emoji_reaction', 'move'));
