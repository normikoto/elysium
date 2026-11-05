-- An actor stops being served, and stops being trusted, once either of these is set. A suspended actor can be brought back.
-- A deleted local actor keeps its row, so that nobody can sign up with a username and URI that other servers still know.
ALTER TABLE actor
    ADD COLUMN suspended_at TIMESTAMPTZ,
    ADD COLUMN deleted_at   TIMESTAMPTZ;

-- Finding whether an inbox belongs to a suspended actor is done for every delivery, and very few actors are suspended.
CREATE INDEX actor_suspended_inbox_idx ON actor (inbox_uri) WHERE suspended_at IS NOT NULL;

-- Activities that tell other servers about the suspension or deletion of their actor. Others from a suspended or deleted
-- actor aren't sent.
ALTER TABLE outbound_activity
    ADD COLUMN about_account BOOLEAN NOT NULL DEFAULT FALSE;
