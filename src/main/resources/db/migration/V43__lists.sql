-- A user's list of accounts they follow, which has a timeline of its own.
CREATE TABLE account_list
(
    id             UUID PRIMARY KEY,
    owner_id       UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    title          TEXT        NOT NULL,
    replies_policy TEXT        NOT NULL CHECK (replies_policy IN ('followed', 'list', 'none')),
    exclusive      BOOLEAN     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (id, owner_id)
);
CREATE INDEX account_list_owner_idx ON account_list (owner_id, created_at, id);

-- A member has to be followed, or asked to be, by the list's owner, so that whatever removes the follow, such as an
-- unfollow, a block, or cleaning up after an account or a server, takes the account out of the owner's lists too.
CREATE TABLE account_list_member
(
    id         UUID PRIMARY KEY,
    list_id    UUID        NOT NULL,
    account_id UUID        NOT NULL,
    target_id  UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (list_id, account_id) REFERENCES account_list (id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (account_id, target_id) REFERENCES follow (follower_id, followed_id) ON DELETE CASCADE,
    UNIQUE (list_id, target_id)
);
CREATE INDEX account_list_member_list_idx ON account_list_member (list_id, created_at, id);
CREATE INDEX account_list_member_follow_idx ON account_list_member (account_id, target_id);
