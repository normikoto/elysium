CREATE TABLE status
(
    id                     UUID PRIMARY KEY,
    uri                    TEXT        NOT NULL UNIQUE,
    actor_id               UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    text                   TEXT        NOT NULL,
    content                TEXT        NOT NULL,
    spoiler_text           TEXT        NOT NULL DEFAULT '',
    language               TEXT,
    visibility             TEXT        NOT NULL CHECK (visibility IN ('public', 'unlisted', 'private', 'direct')),
    sensitive              BOOLEAN     NOT NULL DEFAULT false,
    in_reply_to_id         UUID REFERENCES status (id) ON DELETE SET NULL,
    -- Kept separately so that a reply is still a reply after its parent is deleted.
    in_reply_to_account_id UUID REFERENCES actor (id) ON DELETE SET NULL,
    published_at           TIMESTAMPTZ NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX status_actor_idx ON status (actor_id, id DESC);
CREATE INDEX status_public_idx ON status (id DESC) WHERE visibility = 'public';
CREATE INDEX status_in_reply_to_idx ON status (in_reply_to_id);
