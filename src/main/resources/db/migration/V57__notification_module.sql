-- The inbox module became the notification module, since "inbox" also names the ActivityPub inbox. Its tables, and
-- markers, which moved to the timeline module, lose the inbox prefix.
--
-- Event publications are stored with the names of their event's class and their listener's method, so ones that hadn't
-- completed would otherwise never be retried, since neither name exists any more.
UPDATE event_publication
SET listener_id = replace(listener_id, 'dev.biribiri.elysium.inbox.InboxProjection.',
                          'dev.biribiri.elysium.notification.Notifier.');

UPDATE event_publication
SET listener_id = replace(listener_id, 'dev.biribiri.elysium.inbox.', 'dev.biribiri.elysium.notification.'),
    event_type  = replace(event_type, 'dev.biribiri.elysium.inbox.', 'dev.biribiri.elysium.notification.');

-- Constraint and index names are found rather than listed, since which constraints have names depends on the version of
-- PostgreSQL: from 18, NOT NULL ones do too. Renaming a primary key or unique constraint renames its index as well.
DO
$$
    DECLARE
        renamed     RECORD;
        table_oid   OID;
        constraint_ RECORD;
        index_      RECORD;
    BEGIN
        FOR renamed IN SELECT *
                       FROM (VALUES ('inbox_notification', 'notification'),
                                    ('inbox_follow_group', 'notification_follow_group'),
                                    ('inbox_sequence', 'notification_sequence'),
                                    ('inbox_conversation', 'conversation'),
                                    ('inbox_conversation_status', 'conversation_status'),
                                    ('inbox_marker', 'marker')) AS t(old_table, new_table)
            LOOP
                table_oid := to_regclass(quote_ident(renamed.old_table));
                EXECUTE format('ALTER TABLE %I RENAME TO %I', renamed.old_table, renamed.new_table);
                FOR constraint_ IN SELECT conname FROM pg_constraint
                                   WHERE conrelid = table_oid AND starts_with(conname, renamed.old_table || '_')
                    LOOP
                        EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I', renamed.new_table,
                                       constraint_.conname,
                                       renamed.new_table || substr(constraint_.conname, length(renamed.old_table) + 1));
                    END LOOP;
                FOR index_ IN SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                              WHERE i.indrelid = table_oid AND starts_with(c.relname, renamed.old_table || '_')
                    LOOP
                        EXECUTE format('ALTER INDEX %I RENAME TO %I', index_.relname,
                                       renamed.new_table || substr(index_.relname, length(renamed.old_table) + 1));
                    END LOOP;
            END LOOP;
    END
$$;
