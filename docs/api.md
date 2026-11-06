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
- The length limit defaults to 5000 characters rather than 500. As in Mastodon, each URL counts as 23 characters.
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
- Only a plain domain name can be blocked, not a wildcard, a URL, an IP address, a top-level domain such as `com`, or this
  server's own domain. Blocks always cover subdomains. Some Unicode domains, such as `faß.example`, have to be sent in their
  `xn--` form.
- A domain that a broader block already covers gets a 422, like one that's already blocked.

Accounts follow [Mastodon's admin API](https://docs.joinmastodon.org/methods/admin/accounts/), under `/api/v1/admin/accounts`,
with the `admin:read:accounts` and `admin:write:accounts` scopes, and these differ from Mastodon:

- Only `GET /api/v1/admin/accounts` and `/:id`, `POST /:id/action`, `POST /:id/unsuspend`, and `DELETE /:id` exist. There's no
  `/api/v2/admin/accounts`.
- Listing only filters by `local`, `remote`, `suspended`, `username`, and `by_domain`, isn't ordered by age, and leaves out
  deleted accounts.
- Accounts only have some of Mastodon's fields.
- `type` has to be `suspend`, and other values get a 422.
- `DELETE` doesn't need the account to be suspended first. It's only for local accounts, and gets a 403 for a remote one.
- Suspending or deleting yourself, or the server's last admin, gets a 403.

There's no API for people to delete their own accounts, so that an application can't do it with a token. It's only on the
`/settings/delete-account` page.

API documentation is public, with Swagger UI at `/api-docs/ui` and the OpenAPI document at `/api-docs` or
`/api-docs.yaml`.
