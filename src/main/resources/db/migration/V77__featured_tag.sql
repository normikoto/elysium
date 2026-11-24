-- A hashtag a local account features on its profile, by its normalized name. Like tag_follow, it needs no table of tags,
-- so any valid name can be featured. How many statuses use it is counted when it's listed, rather than kept here, so that
-- deleting or editing statuses has nothing to update. Featured tags aren't federated.
CREATE TABLE featured_tag
(
    id         UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    UNIQUE (account_id, name)
);
