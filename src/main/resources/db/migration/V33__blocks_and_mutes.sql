-- Blocks in both directions: a local account's, whose activity_uri is the Block sent for it, and a remote actor's of a local
-- account, which keeps the ID it came with. activity_at is when that Block first arrived, since jobs have no order and only
-- that can say which of two activities is newer, as for follows.
CREATE TABLE account_block
(
    id           UUID PRIMARY KEY,
    account_id   UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    target_id    UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    activity_uri TEXT        NOT NULL,
    activity_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, target_id),
    CHECK (account_id <> target_id)
);
CREATE INDEX account_block_target_idx ON account_block (target_id);
CREATE INDEX account_block_page_idx ON account_block (account_id, created_at, id);
CREATE INDEX account_block_activity_idx ON account_block (md5(activity_uri));

-- Block activities whose Undo got here first, and what an Undo covers, as undone_follow and undone_follow_pair do.
CREATE TABLE undone_block
(
    activity_uri TEXT        NOT NULL,
    actor_uri    TEXT        NOT NULL,
    undone_at    TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX undone_block_idx ON undone_block (md5(activity_uri), md5(actor_uri));
CREATE INDEX undone_block_undone_at_idx ON undone_block (undone_at);

CREATE TABLE undone_block_pair
(
    actor_uri TEXT        NOT NULL,
    target_id UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    undone_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX undone_block_pair_idx ON undone_block_pair (md5(actor_uri), target_id);
CREATE INDEX undone_block_pair_undone_at_idx ON undone_block_pair (undone_at);

-- Mutes are never federated, so only local accounts have them. An expired one is ignored until it's purged.
CREATE TABLE account_mute
(
    id            UUID PRIMARY KEY,
    account_id    UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    target_id     UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    notifications BOOLEAN     NOT NULL DEFAULT TRUE,
    expires_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, target_id),
    CHECK (account_id <> target_id)
);
CREATE INDEX account_mute_target_idx ON account_mute (target_id);
CREATE INDEX account_mute_page_idx ON account_mute (account_id, created_at, id);
CREATE INDEX account_mute_expires_at_idx ON account_mute (expires_at) WHERE expires_at IS NOT NULL;

-- A local account's block of a whole server, which also covers its subdomains. The domain is lowercase ASCII with no port or
-- trailing dot, as moderation.DomainName leaves it.
CREATE TABLE account_domain_block
(
    id         UUID PRIMARY KEY,
    account_id UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    domain     TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, domain)
);
CREATE INDEX account_domain_block_page_idx ON account_domain_block (account_id, created_at, id);
