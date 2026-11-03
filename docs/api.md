# Using the API

Applications authenticate with OAuth 2, following
[Mastodon's OAuth API](https://docs.joinmastodon.org/methods/oauth/): `POST /api/v1/apps`, `GET /oauth/authorize`,
`POST /oauth/token`, and `POST /oauth/revoke`, with discovery at `/.well-known/oauth-authorization-server`. Elysium
differs from Mastodon in these ways:

- `force_login`, `prompt`, `lang`, `/oauth/userinfo`, and `POST /api/v1/accounts` aren't implemented.
- Each authorization issues a new token rather than reusing the application's existing one.
- Revoking an unknown token succeeds, rather than returning the 403 that Mastodon documents.
- `/oauth/token` and `/oauth/revoke` only accept form-encoded bodies, not JSON.

Statuses follow [Mastodon's statuses API](https://docs.joinmastodon.org/methods/statuses/), with these differences:

- Statuses can't be edited, and media, polls, scheduled posts, and `GET /api/v1/statuses/:id/context` aren't implemented.
- The length limit defaults to 5000 characters rather than 500. As in Mastodon, each URL counts as 23 characters, unless
  it's longer than 2048 characters, in which case it counts at its full length. URLs can save at most 4096 characters
  per status, and any URLs after that also count at their full length.
- `visibility` defaults to `public` only when it's missing. An empty value is rejected, as are a repeated value and
  `visibility[]`.
- `GET /api/v1/instance` reports the limit in `max_toot_chars` and `configuration.statuses.max_characters`, but
  `/api/v2/instance` isn't implemented. Clients that don't read the limit from v1 may apply their own limit of 500.
- `Idempotency-Key` is accepted but ignored, so a client that retries a request can create a duplicate status.

API documentation is public, with Swagger UI at `/api-docs/ui` and the OpenAPI document at `/api-docs` or
`/api-docs.yaml`.
