-- A block either cuts a server off (suspend), or only limits it (noop), as Mastodon's severities do. reject_media keeps a
-- server's attachments from being downloaded, and a suspension rejects them anyway.
ALTER TABLE domain_block
    ADD COLUMN severity     TEXT    NOT NULL DEFAULT 'suspend' CHECK (severity IN ('suspend', 'noop')),
    ADD COLUMN reject_media BOOLEAN NOT NULL DEFAULT false;

-- When an admin started rejecting a remote account's attachments, or null if they aren't.
ALTER TABLE actor ADD COLUMN media_rejected_at TIMESTAMPTZ;
