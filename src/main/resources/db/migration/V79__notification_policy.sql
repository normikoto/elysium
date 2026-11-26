-- Mastodon's notification policies. An account without a row has the defaults, which filter private mentions from
-- accounts it doesn't follow, so accounts from before this get them too.
CREATE TABLE notification_policy
(
    account_id           UUID PRIMARY KEY REFERENCES actor (id) ON DELETE CASCADE,
    for_not_following    TEXT NOT NULL DEFAULT 'accept' CHECK (for_not_following IN ('accept', 'filter', 'drop')),
    for_not_followers    TEXT NOT NULL DEFAULT 'accept' CHECK (for_not_followers IN ('accept', 'filter', 'drop')),
    for_new_accounts     TEXT NOT NULL DEFAULT 'accept' CHECK (for_new_accounts IN ('accept', 'filter', 'drop')),
    for_private_mentions TEXT NOT NULL DEFAULT 'filter' CHECK (for_private_mentions IN ('accept', 'filter', 'drop')),
    for_limited_accounts TEXT NOT NULL DEFAULT 'filter' CHECK (for_limited_accounts IN ('accept', 'filter', 'drop')),
    for_bots             TEXT NOT NULL DEFAULT 'accept' CHECK (for_bots IN ('accept', 'filter', 'drop'))
);

-- A filtered notification is kept out of the account's notifications until it accepts its sender's request.
ALTER TABLE notification ADD COLUMN filtered BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX notification_filtered_idx ON notification (recipient_id, sender_id, id DESC) WHERE filtered AND NOT dismissed;

-- One for each sender with filtered mentions or quotes waiting. Everything a client sees of it but its ID and when it was
-- made is read from those notifications, so a row whose notifications have all gone is never listed.
CREATE TABLE notification_request
(
    id           UUID PRIMARY KEY,
    recipient_id UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    sender_id    UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL,
    UNIQUE (recipient_id, sender_id)
);
CREATE INDEX notification_request_page_idx ON notification_request (recipient_id, id DESC);
CREATE INDEX notification_request_sender_idx ON notification_request (sender_id);

-- A sender whose request was accepted, whose notifications are never filtered or dropped by a policy again.
CREATE TABLE notification_permission
(
    recipient_id UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    sender_id    UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (recipient_id, sender_id)
);
CREATE INDEX notification_permission_sender_idx ON notification_permission (sender_id);
