-- Who a status mentions, which is also who can see it when it's followers-only or direct. A silent mention is an account a
-- remote status was addressed to without a Mention tag: it can see the status, but the status doesn't show it as mentioned.
CREATE TABLE status_mention
(
    status_id UUID    NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    actor_id  UUID    NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    silent    BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (status_id, actor_id)
);

-- For the home timeline, and for the statuses an account is mentioned in.
CREATE INDEX status_mention_actor_idx ON status_mention (actor_id, status_id DESC);
