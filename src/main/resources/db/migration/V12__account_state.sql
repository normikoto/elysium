-- An actor stops being served, and stops being trusted, once either of these is set. A suspended actor can be brought back.
-- A deleted local actor keeps its row, so that nobody can sign up with a username and URI that other servers still know.
ALTER TABLE actor
    ADD COLUMN suspended_at TIMESTAMPTZ,
    ADD COLUMN deleted_at   TIMESTAMPTZ;

-- Finding whether an inbox belongs to a suspended actor is done for every delivery, and very few actors are suspended.
CREATE INDEX actor_suspended_inbox_idx ON actor (inbox_uri) WHERE suspended_at IS NOT NULL;

-- What happens to an activity if its actor is suspended before it's sent. See OutboundActivity.WhileSuspended.
ALTER TABLE outbound_activity
    ADD COLUMN while_suspended TEXT NOT NULL DEFAULT 'WAIT' CHECK (while_suspended IN ('DROP', 'WAIT', 'SEND'));
