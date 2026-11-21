-- Announcements that admins write for this server's own accounts, which Mastodon clients show above the home timeline.
-- published_at is null for a draft. starts_at and ends_at are when what's announced happens, for clients to show, and
-- don't decide whether it's listed, as on Mastodon.
CREATE TABLE announcement
(
    id           UUID PRIMARY KEY,
    text         TEXT        NOT NULL,
    starts_at    TIMESTAMPTZ,
    ends_at      TIMESTAMPTZ,
    all_day      BOOLEAN     NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CHECK ((starts_at IS NULL) = (ends_at IS NULL))
);

-- Which accounts have dismissed which announcements, for the read flag.
CREATE TABLE announcement_dismissal
(
    announcement_id UUID NOT NULL REFERENCES announcement (id) ON DELETE CASCADE,
    actor_id        UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    PRIMARY KEY (announcement_id, actor_id)
);

CREATE INDEX announcement_dismissal_actor_idx ON announcement_dismissal (actor_id);

-- Local accounts' emoji reactions to announcements, as status_reaction has them for statuses: name is the Unicode emoji
-- or the custom emoji's shortcode. Only this server's custom emoji can be used, and deleting one drops its reactions.
CREATE TABLE announcement_reaction
(
    announcement_id UUID        NOT NULL REFERENCES announcement (id) ON DELETE CASCADE,
    actor_id        UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    name            TEXT        NOT NULL,
    emoji_id        UUID REFERENCES custom_emoji (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL,
    UNIQUE NULLS NOT DISTINCT (announcement_id, actor_id, name, emoji_id)
);

CREATE INDEX announcement_reaction_actor_idx ON announcement_reaction (actor_id);
CREATE INDEX announcement_reaction_emoji_idx ON announcement_reaction (emoji_id) WHERE emoji_id IS NOT NULL;
