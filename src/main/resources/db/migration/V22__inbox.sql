-- Thread identities survive deletion of their first status. URI aliases also join replies whose parent arrives later.
ALTER TABLE status ADD COLUMN thread_id UUID NOT NULL DEFAULT gen_random_uuid();
UPDATE status SET thread_id = id;
CREATE INDEX status_thread_idx ON status (thread_id, id);
CREATE TABLE status_thread_alias
(
    uri TEXT PRIMARY KEY,
    thread_id UUID NOT NULL,
    actor_id UUID REFERENCES actor (id) ON DELETE CASCADE
);
CREATE INDEX status_thread_alias_thread_idx ON status_thread_alias (thread_id);
CREATE INDEX status_thread_alias_actor_idx ON status_thread_alias (actor_id);
INSERT INTO status_thread_alias SELECT uri, thread_id, actor_id FROM status;
INSERT INTO status_thread_alias (uri, thread_id)
SELECT in_reply_to_uri, (array_agg(id ORDER BY id))[1] FROM status
WHERE in_reply_to_uri IS NOT NULL GROUP BY in_reply_to_uri ON CONFLICT DO NOTHING;

DO $$
DECLARE left_id UUID; right_id UUID; keep_id UUID; remove_id UUID;
BEGIN
    LOOP
        SELECT a.thread_id, b.thread_id INTO left_id, right_id
        FROM status s
        LEFT JOIN status p ON p.id = s.in_reply_to_id
        JOIN status_thread_alias a ON a.uri = s.uri
        JOIN status_thread_alias b ON b.uri = coalesce(s.in_reply_to_uri, p.uri)
        WHERE a.thread_id <> b.thread_id LIMIT 1;
        EXIT WHEN NOT FOUND;
        keep_id := least(left_id, right_id);
        remove_id := greatest(left_id, right_id);
        UPDATE status_thread_alias SET thread_id = keep_id WHERE thread_id = remove_id;
        UPDATE status SET thread_id = keep_id WHERE thread_id = remove_id;
    END LOOP;
END $$;

-- The checkpoint survives clear/dismiss and status cleanup, so notification cursors never move backwards.
CREATE TABLE inbox_sequence
(
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    last_id UUID NOT NULL
);
INSERT INTO inbox_sequence VALUES (TRUE, '00000000-0000-0000-0000-000000000000');
CREATE TABLE inbox_notification
(
    id UUID PRIMARY KEY,
    recipient_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('mention', 'follow', 'follow_request')),
    source_id UUID NOT NULL,
    status_id UUID REFERENCES status (id) ON DELETE CASCADE,
    follow_id UUID REFERENCES follow (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    group_key TEXT,
    -- Keep the source receipt after dismissal: event retries must not bring a dismissed notification back.
    dismissed BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (recipient_id, type, source_id)
);
CREATE INDEX inbox_notification_page_idx ON inbox_notification (recipient_id, id DESC) WHERE NOT dismissed;
CREATE INDEX inbox_notification_group_idx ON inbox_notification (recipient_id, group_key, id DESC) WHERE NOT dismissed;
CREATE INDEX inbox_notification_sender_idx ON inbox_notification (sender_id);
CREATE INDEX inbox_notification_status_idx ON inbox_notification (status_id);
CREATE INDEX inbox_notification_follow_idx ON inbox_notification (follow_id);
CREATE TABLE inbox_follow_group
(
    recipient_id UUID PRIMARY KEY REFERENCES actor (id) ON DELETE CASCADE,
    start_hour BIGINT NOT NULL
);
CREATE TABLE inbox_conversation
(
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    thread_id UUID NOT NULL,
    participant_ids UUID[] NOT NULL,
    last_read_id UUID,
    hidden_through_id UUID,
    UNIQUE (owner_id, thread_id, participant_ids)
);
CREATE INDEX inbox_conversation_owner_idx ON inbox_conversation (owner_id);
CREATE INDEX inbox_conversation_thread_idx ON inbox_conversation (thread_id);
CREATE INDEX inbox_conversation_participants_idx ON inbox_conversation USING gin (participant_ids);
CREATE TABLE inbox_conversation_status
(
    conversation_id UUID NOT NULL REFERENCES inbox_conversation (id) ON DELETE CASCADE,
    status_id UUID NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    PRIMARY KEY (conversation_id, status_id)
);
CREATE INDEX inbox_conversation_status_status_idx ON inbox_conversation_status (status_id);
CREATE TABLE inbox_marker
(
    owner_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    timeline TEXT NOT NULL CHECK (timeline IN ('home', 'notifications')),
    last_read_id UUID NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, timeline)
);

-- Existing messages stay accessible when the home timeline's temporary mention exception is removed.
CREATE TEMP TABLE inbox_backfill ON COMMIT DROP AS
SELECT s.id AS status_id, s.thread_id, recipient.id AS owner_id,
       ARRAY(SELECT DISTINCT participant FROM (
           SELECT s.actor_id AS participant
           UNION ALL SELECT actor_id FROM status_mention WHERE status_id = s.id AND NOT silent
       ) people WHERE participant <> recipient.id ORDER BY participant) AS participant_ids
FROM status s
JOIN actor author ON author.id = s.actor_id AND author.deleted_at IS NULL
JOIN actor recipient ON recipient.is_local AND recipient.deleted_at IS NULL
    AND (recipient.id = s.actor_id OR EXISTS (
        SELECT 1 FROM status_mention m WHERE m.status_id = s.id AND m.actor_id = recipient.id AND NOT m.silent))
WHERE s.visibility = 'direct';
INSERT INTO inbox_conversation (id, owner_id, thread_id, participant_ids, last_read_id)
SELECT gen_random_uuid(), owner_id, thread_id, participant_ids, (array_agg(status_id ORDER BY status_id DESC))[1]
FROM inbox_backfill GROUP BY owner_id, thread_id, participant_ids;
INSERT INTO inbox_conversation_status
SELECT c.id, b.status_id FROM inbox_backfill b JOIN inbox_conversation c
ON c.owner_id = b.owner_id AND c.thread_id = b.thread_id AND c.participant_ids = b.participant_ids;
