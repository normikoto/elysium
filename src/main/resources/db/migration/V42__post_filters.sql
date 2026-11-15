-- A user's filter, which hides or warns about statuses in the contexts it lists. An expired one is kept, since setting a
-- new expiry turns it back on, as on Mastodon.
CREATE TABLE post_filter
(
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    context TEXT[] NOT NULL CHECK (cardinality(context) > 0
        AND context <@ ARRAY ['home', 'notifications', 'public', 'thread', 'account']),
    action TEXT NOT NULL CHECK (action IN ('warn', 'hide', 'blur')),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX post_filter_account_idx ON post_filter (account_id, created_at, id);

-- The v1 API knows each keyword as a filter of its own, by the keyword's ID.
CREATE TABLE post_filter_keyword
(
    id UUID PRIMARY KEY,
    filter_id UUID NOT NULL REFERENCES post_filter (id) ON DELETE CASCADE,
    keyword TEXT NOT NULL,
    whole_word BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX post_filter_keyword_filter_idx ON post_filter_keyword (filter_id, created_at, id);

CREATE TABLE post_filter_status
(
    id UUID PRIMARY KEY,
    filter_id UUID NOT NULL REFERENCES post_filter (id) ON DELETE CASCADE,
    status_id UUID NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (filter_id, status_id)
);
CREATE INDEX post_filter_status_status_idx ON post_filter_status (status_id);
