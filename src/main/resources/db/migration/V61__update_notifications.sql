ALTER TABLE notification DROP CONSTRAINT notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN ('mention', 'follow', 'follow_request', 'favourite', 'reblog', 'quote', 'quoted_update',
                    'admin.report', 'poll', 'pleroma:emoji_reaction', 'move', 'pending.quote', 'update'));
