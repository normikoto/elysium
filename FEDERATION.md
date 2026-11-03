# Federation

Elysium is an early work in progress and can't federate yet. Other servers can discover its users, but it doesn't send
or receive activities. This file only describes what's implemented today.

## Supported federation protocols and standards

- [ActivityPub](https://www.w3.org/TR/activitypub/) (Server-to-Server): actor documents only
- [WebFinger](https://webfinger.net/)

## Supported FEPs

- [FEP-67ff: FEDERATION.md](https://codeberg.org/fediverse/fep/src/branch/main/fep/67ff/fep-67ff.md)

## ActivityPub

### Actors

Local users are `Person` actors at `/users/{username}`. These documents are served as `application/activity+json` or
`application/ld+json; profile="https://www.w3.org/ns/activitystreams"`, and any origin can read them through CORS. A
request has to ask for one of these types in its `Accept` header. Anything else, including `*/*` or no header, gets the
user's HTML profile page.

Each actor document includes `publicKey` with an RSA 2048 key, identified as `{actor-uri}#main-key`.

Usernames are 1 to 30 letters, digits, or underscores, and are matched case-insensitively.

### Activities

No activities are sent or received yet.

### Notable differences

- Actor documents advertise `inbox` and `outbox`, but those endpoints aren't implemented yet, so actors can't be
  followed.
- Actor documents don't include `followers` or `following` collections.
- Status URIs (`/users/{username}/statuses/{id}`) only serve an HTML page, not an ActivityPub object.
- There's no shared inbox.
- HTTP Signatures aren't implemented, so requests are neither signed nor verified.

## WebFinger

`/.well-known/webfinger` accepts `acct:{username}@{domain}` and actor URLs as the `resource`. Responses are served as
`application/jrd+json` or `application/json`, include a `self` link to the actor document, and can be read from any
origin through CORS.

## Additional documentation

- [README](README.md)
