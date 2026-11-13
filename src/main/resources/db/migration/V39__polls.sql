-- A status's poll. ended_at is set once the poll has ended and that's been acted on, which is what the job that ends polls
-- looks for. For a poll from another server, voters_count and poll_option.votes_count are what its server last said,
-- plus local votes since, and fetched_at is when that was. A local poll's counts come from poll_vote instead.
CREATE TABLE poll
(
    status_id    UUID PRIMARY KEY REFERENCES status (id) ON DELETE CASCADE,
    multiple     BOOLEAN     NOT NULL,
    hide_totals  BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at   TIMESTAMPTZ,
    closed_at    TIMESTAMPTZ,
    ended_at     TIMESTAMPTZ,
    voters_count INTEGER,
    fetched_at   TIMESTAMPTZ
);

CREATE INDEX poll_ending_idx ON poll (least(closed_at, expires_at)) WHERE ended_at IS NULL;

CREATE TABLE poll_option
(
    status_id   UUID     NOT NULL REFERENCES poll (status_id) ON DELETE CASCADE,
    position    SMALLINT NOT NULL,
    title       TEXT     NOT NULL,
    votes_count INTEGER,
    PRIMARY KEY (status_id, position)
);

-- uri is set for a vote from another server, which it's sent with, so that a repeated delivery isn't counted twice.
CREATE TABLE poll_vote
(
    id         UUID PRIMARY KEY,
    status_id  UUID        NOT NULL REFERENCES poll (status_id) ON DELETE CASCADE,
    voter_id   UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    position   SMALLINT    NOT NULL,
    uri        TEXT UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (status_id, voter_id, position)
);

CREATE INDEX poll_vote_voter_idx ON poll_vote (voter_id);

ALTER TABLE status_edit ADD COLUMN poll_options TEXT[];

ALTER TABLE inbox_notification DROP CONSTRAINT inbox_notification_type_check;
ALTER TABLE inbox_notification ADD CONSTRAINT inbox_notification_type_check
    CHECK (type IN ('mention', 'follow', 'follow_request', 'favourite', 'reblog', 'quote', 'quoted_update',
                    'admin.report', 'poll'));
