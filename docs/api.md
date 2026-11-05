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

Domain blocks follow [Mastodon's admin API](https://docs.joinmastodon.org/methods/admin/domain_blocks/), under
`/api/v1/admin/domain_blocks`, with the `admin:read:domain_blocks` and `admin:write:domain_blocks` scopes. Every block is a
full block, so these differ from Mastodon:

- `severity` is required when creating a block, and has to be `suspend`. Mastodon's default is `silence`, and `silence` and
  `noop` get a 422 here rather than cutting off a server that an admin only meant to limit.
- `reject_media` and `reject_reports` are always returned as `true`, `obfuscate` as `false`, and `public_comment` as null.
  Any values sent for them are ignored.
- A domain with a wildcard, a port, or a URL's other parts is refused, as are IP addresses, a domain with only one label such
  as `com`, and this server's own domain or one above it. Blocks always cover subdomains and every port.
- `private_comment` can be up to 1000 characters.
- A domain that a broader block already covers gets a 422 with `existing_domain_block`, like one that's already blocked.

API documentation is public, with Swagger UI at `/api-docs/ui` and the OpenAPI document at `/api-docs` or
`/api-docs.yaml`.
