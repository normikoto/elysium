-- A local account's private memo about another account, which only it sees, and which Mastodon's API calls a note. Memos
-- are never federated, so only local accounts have them.
CREATE TABLE account_memo
(
    account_id UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    target_id  UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    memo       TEXT        NOT NULL CHECK (memo <> ''),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, target_id)
);
CREATE INDEX account_memo_target_idx ON account_memo (target_id);
