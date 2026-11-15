-- Profiles of accounts on other servers, read from their actor documents. Their bio and field values arrive as HTML, which
-- is kept sanitized in note_html and value_html, while note and value hold its text, as a local profile's do. Images are
-- downloaded from the *_remote_url columns, and linked to until they are, or for good once *_cache_given_up is set.
ALTER TABLE profile
    ADD COLUMN note_html               TEXT,
    ADD COLUMN avatar_remote_url       TEXT,
    ADD COLUMN avatar_cache_given_up   BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN header_remote_url       TEXT,
    ADD COLUMN header_cache_given_up   BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN url                     TEXT,
    ADD COLUMN bot                     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN group_actor             BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN published               TIMESTAMPTZ;

ALTER TABLE profile_field
    ADD COLUMN value_html TEXT;

-- The custom emoji that another server's actor document lists. A local profile's are found from its text instead. Each
-- keeps its emoji from being removed as unused.
CREATE TABLE profile_emoji
(
    actor_id UUID NOT NULL REFERENCES profile (actor_id) ON DELETE CASCADE,
    emoji_id UUID NOT NULL REFERENCES custom_emoji (id) ON DELETE CASCADE,
    PRIMARY KEY (actor_id, emoji_id)
);

CREATE INDEX profile_emoji_emoji_idx ON profile_emoji (emoji_id);
