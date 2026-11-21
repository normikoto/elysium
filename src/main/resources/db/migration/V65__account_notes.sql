-- A local account's private note about another account, which only it sees. Notes are never federated, so only local
-- accounts have them.
CREATE TABLE account_note
(
    account_id UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    target_id  UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    note       TEXT        NOT NULL CHECK (note <> ''),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, target_id)
);
CREATE INDEX account_note_target_idx ON account_note (target_id);
