# Federation

Elysium is an early work in progress and can't federate yet. Other servers can discover its users, but it doesn't send
or receive activities. This file only describes what's implemented today.

## Supported federation protocols and standards

- [ActivityPub](https://www.w3.org/TR/activitypub/) (Server-to-Server): fetching actors and `Note` objects only
- [WebFinger](https://webfinger.net/)
- [NodeInfo](https://nodeinfo.diaspora.software/) 2.0 and 2.1

## Supported FEPs

- [FEP-67ff: FEDERATION.md](https://codeberg.org/fediverse/fep/src/branch/main/fep/67ff/fep-67ff.md)
- [FEP-f1d5: NodeInfo in Fediverse Software](https://codeberg.org/fediverse/fep/src/branch/main/fep/f1d5/fep-f1d5.md)

## ActivityPub

### Actors

Local users are `Person` actors at `/users/{username}`. These documents are served as `application/activity+json` or
`application/ld+json; profile="https://www.w3.org/ns/activitystreams"`, and any origin can read them through CORS. A
request has to ask for one of these types in its `Accept` header, and `application/ld+json` is accepted with any profile.
Requests that accept HTML instead, including `*/*` or no header, get the user's profile page, and others get a 406. The
document's `url` links to that page, which is at the same URL.

Each actor document includes `publicKey` with an RSA 2048 key, identified as `{actor-uri}#main-key`.

Usernames are 1 to 30 letters, digits, or underscores, and are matched case-insensitively.

### Objects

Public and unlisted statuses are `Note` objects at `/users/{username}/statuses/{id}`, which is also the URL of their web
page. These are served the same way as actor documents: the same `Accept` types get the object, requests that accept HTML
get the page, and any origin can read both through CORS. Private and direct statuses, and deleted statuses, get a 404.

A `Note` has `id`, `attributedTo`, `url`, `published`, `to`, `cc`, `content`, and `sensitive`, which is defined in its
`@context` as `as:sensitive`. It can also have:

- `summary`: the content warning, as plain text rather than HTML, which matches Mastodon.
- `inReplyTo`: the parent status.
- `contentMap`: the `content` under its language, if one is set.

Public statuses have the public collection in `to`, and unlisted statuses have it in `cc`.

### Activities

No activities are sent or received yet.

### Notable differences

- Actor documents advertise `inbox` and `outbox`, but those endpoints aren't implemented yet, so actors can't be
  followed.
- Actor documents don't include `followers` or `following` collections.
- `Note` objects aren't addressed to followers, because actors don't have a followers collection yet.
- `Note` objects for replies don't address the parent's author or include a `Mention` tag.
- A reply's `inReplyTo` is left out if its parent is private, direct, or deleted, so it looks like it isn't a reply.
- Deleted statuses get a 404, not a `Tombstone`.
- There's no shared inbox.
- HTTP Signatures aren't implemented, so requests are neither signed nor verified.

## WebFinger

`/.well-known/webfinger` accepts `acct:{username}@{domain}` and actor URLs as the `resource`. Responses are served as
`application/jrd+json` or `application/json`, include a `self` link to the actor document and a
`http://webfinger.net/rel/profile-page` link to the user's profile page, and can be read from any origin through CORS.

## NodeInfo

`/.well-known/nodeinfo` links to NodeInfo documents at `/nodeinfo/2.1` and `/nodeinfo/2.0`, and all of these can be read
from any origin through CORS. The documents match, except that only 2.1 includes the software's repository. They report
`activitypub` as the protocol, even though federation isn't complete yet. Their usage statistics include the total number
of local users and `localPosts`, which counts replies but not direct messages. Active user counts and `localComments`
aren't reported.

## Additional documentation

- [README](README.md)
