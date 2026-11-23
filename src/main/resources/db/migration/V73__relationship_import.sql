-- A local account's import of the accounts it follows, blocks, or mutes from a CSV file, as Mastodon's export writes them.
-- The addresses are kept here rather than in the job's data, which admins can see. Each row goes once it's been handled,
-- and failed counts the ones that couldn't be. finished_at is null while rows are left, and an account has only one import
-- running at a time. A finished one is kept, to show how it went, until the account starts another.
CREATE TABLE relationship_import
(
    id          UUID PRIMARY KEY,
    account_id  UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    kind        TEXT        NOT NULL CHECK (kind IN ('follows', 'blocks', 'mutes')),
    total       INTEGER     NOT NULL,
    failed      INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE INDEX relationship_import_account_idx ON relationship_import (account_id);
CREATE UNIQUE INDEX relationship_import_running_idx ON relationship_import (account_id) WHERE finished_at IS NULL;

-- show_reblogs, notify, and languages are a follow's options, and hide_notifications a mute's. Each is null when the file
-- didn't give it, which leaves it at its default. An empty languages array means every language.
CREATE TABLE relationship_import_row
(
    import_id          UUID    NOT NULL REFERENCES relationship_import (id) ON DELETE CASCADE,
    position           INTEGER NOT NULL,
    username           TEXT    NOT NULL,
    domain             TEXT,
    show_reblogs       BOOLEAN,
    notify             BOOLEAN,
    languages          TEXT[],
    hide_notifications BOOLEAN,
    PRIMARY KEY (import_id, position)
);
