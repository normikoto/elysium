-- Custom emoji, this server's own and other servers'. A local emoji's domain is null, and its image is the one uploaded.
-- Another server's are matched by shortcode under the domain of the actor that used them, as Mastodon matches them, so
-- that a server can only define its own. image_name and static_name are files in the media directory, which another
-- server's emoji has none of until it's downloaded, and remote_url is where its server has the image.
CREATE TABLE custom_emoji
(
    id                UUID PRIMARY KEY,
    shortcode         TEXT        NOT NULL,
    domain            TEXT,
    uri               TEXT        NOT NULL,
    image_name        TEXT,
    static_name       TEXT,
    remote_url        TEXT,
    -- Set when another server's image couldn't or mustn't be downloaded, so it's only linked.
    cache_given_up    BOOLEAN     NOT NULL DEFAULT false,
    category          TEXT,
    visible_in_picker BOOLEAN     NOT NULL DEFAULT true,
    -- When its server last said the emoji changed, which decides whether a newer image replaces the one kept.
    remote_updated    TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- When another server's emoji was last stored as used, which keeps it from being removed as unused while the status
    -- that uses it is still being stored.
    seen_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (shortcode, domain),
    CHECK ((static_name IS NULL) = (image_name IS NULL)),
    CHECK (domain IS NOT NULL OR image_name IS NOT NULL),
    CHECK (domain IS NULL OR remote_url IS NOT NULL)
);

CREATE INDEX custom_emoji_domain_idx ON custom_emoji (domain) WHERE domain IS NOT NULL;

-- The custom emoji a status shows, which are those its text names, matched when it's written or received. Removing an
-- emoji removes it from statuses, which then show the shortcode as text.
CREATE TABLE status_emoji
(
    status_id UUID NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    emoji_id  UUID NOT NULL REFERENCES custom_emoji (id) ON DELETE CASCADE,
    PRIMARY KEY (status_id, emoji_id)
);

CREATE INDEX status_emoji_emoji_idx ON status_emoji (emoji_id);

-- The emoji each earlier version showed. They aren't references, and don't keep another server's emoji from being removed
-- as unused, so a version only shows those that are still there.
ALTER TABLE status_edit ADD COLUMN emoji_ids UUID[];
