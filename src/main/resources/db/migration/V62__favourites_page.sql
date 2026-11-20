-- GET /api/v1/favourites pages an account's likes by their IDs, which the (actor_id, status_id) constraint can't serve.
CREATE INDEX status_like_actor_page_idx ON status_like (actor_id, id DESC);
