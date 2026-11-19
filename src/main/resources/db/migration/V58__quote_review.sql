-- When the quote was last asked for: by this server's QuoteRequest for a local quote, or for a quote of a local status, when
-- it was put to its author to review by hand. Null for a remote quote of a local status that hasn't been asked for yet.
ALTER TABLE status_quote ADD COLUMN requested_at TIMESTAMPTZ;
-- When the quote was last accepted, rejected, or revoked. Null while it's pending, and for quotes decided before this was
-- added, so that no existing row is rewritten.
ALTER TABLE status_quote ADD COLUMN decided_at TIMESTAMPTZ;
CREATE INDEX status_quote_review_idx ON status_quote (quoted_account_id, status_id DESC)
    WHERE state = 'pending' AND requested_at IS NOT NULL;

-- Whose quotes of a local account's new statuses its author approves by hand: nobody's, those of accounts that don't follow
-- them, or everyone's.
ALTER TABLE posting_default ADD COLUMN quote_review TEXT NOT NULL DEFAULT 'none'
    CHECK (quote_review IN ('none', 'non_followers', 'all'));

ALTER TABLE notification DROP CONSTRAINT notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check
    CHECK (type IN ('mention', 'follow', 'follow_request', 'favourite', 'reblog', 'quote', 'quoted_update',
                    'admin.report', 'poll', 'pleroma:emoji_reaction', 'move', 'pending.quote'));
