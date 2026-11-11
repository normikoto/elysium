-- Who can quote a status, as FEP-044f's canQuote has it: which audiences are approved automatically, and which by hand.
-- Null for a status from before quotes, which only its author can quote if it's local, and for a remote status whose
-- server didn't say, which is taken to be one that can't ask for consent. Nullable with no default, so nothing is rewritten.
ALTER TABLE status
    ADD COLUMN quote_automatic TEXT[],
    ADD COLUMN quote_manual    TEXT[],
    -- When this server last sent an Update of a local status for something other than an edit, such as a quote being
    -- approved, which has to be later than any version sent before it.
    ADD COLUMN note_updated_at TIMESTAMPTZ;
ALTER TABLE status ADD CONSTRAINT status_quote_automatic_check
    CHECK (quote_automatic <@ ARRAY ['public', 'followers', 'following', 'unsupported']);
ALTER TABLE status ADD CONSTRAINT status_quote_manual_check
    CHECK (quote_manual <@ ARRAY ['public', 'followers', 'following', 'unsupported']);

-- The status that a status quotes. quoted_uri is kept when the quoted status isn't stored, or has gone, and linked tells
-- the two apart: a quote that was linked and has no quoted status any more was of one that's been deleted.
CREATE TABLE status_quote
(
    status_id         UUID PRIMARY KEY REFERENCES status (id) ON DELETE CASCADE,
    quoted_uri        TEXT        NOT NULL,
    quoted_status_id  UUID REFERENCES status (id) ON DELETE SET NULL,
    quoted_account_id UUID REFERENCES actor (id) ON DELETE SET NULL,
    linked            BOOLEAN     NOT NULL DEFAULT FALSE,
    state             TEXT        NOT NULL CHECK (state IN ('pending', 'accepted', 'rejected', 'revoked')),
    -- A quote from a server that can't ask for consent, or of a status on one that can't give it.
    legacy            BOOLEAN     NOT NULL DEFAULT FALSE,
    -- The QuoteAuthorization another server gave, when the quoted status is remote.
    approval_uri      TEXT,
    -- This server's QuoteAuthorization, when the quoted status is local. A new one for each approval, so that a stamp
    -- that's been deleted is never served again.
    stamp_id          UUID UNIQUE,
    -- The QuoteRequest that asked for approval, this server's or another's.
    request_uri       TEXT,
    changed_at        TIMESTAMPTZ NOT NULL,
    CHECK (approval_uri IS NULL OR stamp_id IS NULL)
);
CREATE INDEX status_quote_quoted_idx ON status_quote (quoted_status_id, status_id DESC);
CREATE INDEX status_quote_waiting_idx ON status_quote (md5(quoted_uri)) WHERE NOT linked;
CREATE INDEX status_quote_approval_idx ON status_quote (md5(approval_uri)) WHERE approval_uri IS NOT NULL;
CREATE INDEX status_quote_request_idx ON status_quote (md5(request_uri)) WHERE request_uri IS NOT NULL;
CREATE INDEX status_quote_account_idx ON status_quote (quoted_account_id);

-- What a local account's new statuses get when the client doesn't say.
CREATE TABLE posting_default
(
    actor_id     UUID PRIMARY KEY REFERENCES actor (id) ON DELETE CASCADE,
    quote_policy TEXT NOT NULL CHECK (quote_policy IN ('public', 'followers', 'nobody'))
);

ALTER TABLE inbox_notification DROP CONSTRAINT inbox_notification_type_check;
ALTER TABLE inbox_notification ADD CONSTRAINT inbox_notification_type_check
    CHECK (type IN ('mention', 'follow', 'follow_request', 'favourite', 'reblog', 'quote', 'quoted_update'));
