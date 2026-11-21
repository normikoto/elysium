-- A local account's choices for one account it follows, which Mastodon's follow API sets. They only matter for follows by
-- local accounts, and never federate. languages is null for every language.
ALTER TABLE follow
    ADD COLUMN show_reblogs BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN languages    TEXT[];

ALTER TABLE notification DROP CONSTRAINT notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN ('mention', 'follow', 'follow_request', 'favourite', 'reblog', 'quote', 'quoted_update',
                    'admin.report', 'poll', 'pleroma:emoji_reaction', 'move', 'pending.quote', 'update', 'status'));
