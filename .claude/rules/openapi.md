---
paths:
  - "src/main/java/dev/biribiri/elysium/api/**"
  - "src/main/java/dev/biribiri/elysium/activitypub/web/**"
  - "src/main/java/dev/biribiri/elysium/activitypub/media/MediaProxyController.java"
  - "src/test/java/dev/biribiri/elysium/ApiDocsIntegrationTests.java"
---

# OpenAPI

The OpenAPI document repeats `docs/api.md` and `FEDERATION.md` for each endpoint, so a change to an endpoint's
behavior also changes its annotations. `ApiDocsIntegrationTests` checks that every endpoint is described.

## Client API

- Endpoints have an `@Operation` with a summary, a link to Mastodon's documentation, the differences from
  `docs/api.md` that apply to it, and its error responses.
- `api.web.ApiDocs` fills in what they have in common: the `Error` body for every error, the 401 and 403 that an
  endpoint's scopes imply, form and multipart bodies like the JSON one, and the paging cursors and `Link` header for a
  list that takes `max_id`.
- Request bodies that are read by hand are documented with a private record in the controller, as JSON only. Where a
  form and a JSON mapping share a path, springdoc combines them and uses the JSON one's annotations.

## Federation

- Endpoints follow `FEDERATION.md`.
- `activitypub.web.FederationApiDocs` only keeps a body on a response whose annotation declares one, documents each
  `application/activity+json` body as `application/ld+json` too, and describes the request every inbox takes.
- `InboxController`'s class-level `@ApiResponse`s are what every inbox answers.
