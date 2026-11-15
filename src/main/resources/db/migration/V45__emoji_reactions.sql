-- Emoji reactions to statuses. name is the Unicode emoji, or a custom emoji's shortcode, whose emoji_id says which server's
-- it is. An actor can react to a status with several emoji, but each one once. activity_uri identifies the EmojiReact, or
-- the Like with an emoji that Misskey sends, so that an Undo can find it.
CREATE TABLE status_reaction
(
    id           UUID PRIMARY KEY,
    actor_id     UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    status_id    UUID        NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    name         TEXT        NOT NULL,
    emoji_id     UUID REFERENCES custom_emoji (id) ON DELETE CASCADE,
    activity_uri TEXT        NOT NULL,
    activity_at  TIMESTAMPTZ NOT NULL,
    UNIQUE NULLS NOT DISTINCT (actor_id, status_id, name, emoji_id)
);

CREATE INDEX status_reaction_status_idx ON status_reaction (status_id, id DESC);
CREATE INDEX status_reaction_activity_idx ON status_reaction (md5(activity_uri));
CREATE INDEX status_reaction_emoji_idx ON status_reaction (emoji_id) WHERE emoji_id IS NOT NULL;

-- An Undo can run before its reaction, including before the actor has been fetched.
CREATE TABLE undone_reaction
(
    activity_uri TEXT        NOT NULL,
    actor_uri    TEXT        NOT NULL,
    undone_at    TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX undone_reaction_idx ON undone_reaction (md5(activity_uri), md5(actor_uri));
CREATE INDEX undone_reaction_time_idx ON undone_reaction (undone_at);

ALTER TABLE inbox_notification DROP CONSTRAINT inbox_notification_type_check;
ALTER TABLE inbox_notification ADD CONSTRAINT inbox_notification_type_check
    CHECK (type IN ('mention', 'follow', 'follow_request', 'favourite', 'reblog', 'quote', 'quoted_update',
                    'admin.report', 'poll', 'pleroma:emoji_reaction'));
ALTER TABLE inbox_notification ADD COLUMN reaction_id UUID REFERENCES status_reaction (id) ON DELETE CASCADE;
CREATE INDEX inbox_notification_reaction_idx ON inbox_notification (reaction_id) WHERE reaction_id IS NOT NULL;
