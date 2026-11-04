# Federation

Elysium is an early work in progress and can't fully federate yet. Other servers can discover its users, deliver activities
to them, and follow them, but its users can't follow anyone, and their statuses aren't sent to followers. This file only
describes what's implemented today.

## Supported federation protocols and standards

- [ActivityPub](https://www.w3.org/TR/activitypub/) (Server-to-Server): fetching actors and `Note` objects, accepting
  activities at an inbox, and receiving `Follow` and `Undo` of a `Follow`
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

### Instance actor

The server itself is an `Application` actor at `/actor`, with the `preferredUsername` `instance.actor`. It's for signing
requests that no user is behind, which so far means fetching keys and actor documents. Its document is served as
`application/activity+json` or `application/ld+json` with any profile, and any origin can read it through CORS. It never
requires a signature. There's no web page at this URL, so its `url` links to the front page. Its key is identified as
`{actor-uri}#main-key`, and it isn't counted as a user. It can't be followed, so a `Follow` of it is ignored, with no
`Reject`.

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

### Inboxes

Each local user has an inbox at `/users/{username}/inbox`, the instance actor has one at `/actor/inbox`, and there's a
shared inbox at `/inbox`. They all work the same way, and the shared inbox isn't advertised in actor documents yet. They
accept a `POST` with a `Content-Type` of
`application/activity+json`, or `application/ld+json` with any profile, and a body of up to 1 MiB. Activities are read as
plain JSON, without a JSON-LD processor, and no `@context` is ever fetched.

| Status | Meaning                                                                                                      |
|--------|--------------------------------------------------------------------------------------------------------------|
| `202`  | The activity is queued, it's a repeat of one with the same `id`, or it was dropped because of who signed it  |
| `400`  | The signature header can't be read, the body isn't a UTF-8 JSON object with a string `id`, `type`, and `actor`, or the `id` isn't a valid URI or is over 2048 bytes |
| `401`  | There's no signature in a supported format, or it isn't valid                                                |
| `403`  | The activity's `id` is a valid URI, but not an HTTPS URL on the same host and port as its `actor`            |
| `404`  | The user doesn't exist                                                                                       |
| `413`  | The body is too large                                                                                        |
| `415`  | The `Content-Type` isn't one of those above                                                                  |
| `429`  | This address has caused too many key fetches, and `Retry-After` says when to try again                       |
| `503`  | The sender's key can't be fetched right now                                                                  |

The only limit is on key fetches: requests from one address can start 30 a minute, with 4 running at once. A request only
counts if it starts a fetch, so one signed with a key this server already has isn't limited, and neither is one waiting on
a fetch that's already running. IPv6 addresses are counted by their /64 network.

### Activities

Activities that are accepted are stored, and processed later by a background job, so an inbox's answer doesn't say whether
one worked. Activities are processed in no particular order. Only `Follow` and `Undo` of a `Follow` are acted on, and other
types are ignored.

An activity is only accepted if its `actor`, which can be an array of one, is the actor that signed the request. Otherwise
it's answered with a `202` and dropped, which includes activities forwarded by another server and activities with several
actors. Linked Data Signatures aren't verified.

### Follows

A `Follow` whose `object` is a local user is always accepted, because accounts can't approve followers by hand yet. Who's
following comes from the signature, and not from the activity's body. The `object` can be an ID, an embedded object with an
`id`, or an array of one of those. A `Follow` of anything else is ignored.

The follower's document is fetched, signed as the [instance actor](#instance-actor), unless a copy from the last 24 hours
is stored. A `Follow` is refused, and not tried again, unless the document:

- Has an `id` matching the signer, and an actor `type`: `Application`, `Group`, `Organization`, `Person`, or `Service`.
- Has a `preferredUsername` of up to 255 characters, with no control characters. Other actors on that server can have the
  same one, because an actor's URI is what identifies it.
- Has an `inbox` that's an HTTPS URL on the same host and port as the actor, with no user info. This stops a document from
  aiming this server's requests at another server. An `endpoints.sharedInbox` that isn't one is left out, without refusing
  the actor.
- Is on a different domain from this server.

Each `Follow` that's stored gets an `Accept`, signed as the user being followed, and sent to the follower's `inbox`. This
includes another `Follow` from someone who's already following, since their server may have lost track, whether or not it
has a new `id`. The `Accept`'s `id` is
`{actor-uri}#accepts/follows/{hash}`, where the hash is the SHA-256 of the `Follow`'s `id`, and its `object` is the `Follow`
with its `id`, `type`, `actor`, and `object`:

```json
{
  "@context": "https://www.w3.org/ns/activitystreams",
  "id": "https://elysium.example/users/alice#accepts/follows/2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
  "type": "Accept",
  "actor": "https://elysium.example/users/alice",
  "object": {
    "id": "https://remote.example/activities/1",
    "type": "Follow",
    "actor": "https://remote.example/users/bob",
    "object": "https://elysium.example/users/alice"
  }
}
```

An `Undo` removes a follow if its `object` names the `Follow` that created it, by `id`, or as an embedded `Follow` with an
`id`. An embedded `Follow` with no `id` removes the follow between its actor and its `object`. Nothing happens if:

- The embedded `Follow`'s `actor` isn't the actor that signed the `Undo`.
- The follow came from a different `Follow`, which would be a newer one.
- The `Follow`'s `id` isn't an HTTPS URL on the same host and port as the actor, or is over 2048 bytes.

Activities are ordered by when they arrived here, whatever order they're processed in:

- An `Undo` also removes any follow between the two actors from a `Follow` that arrived before the one it names, and stops
  those from creating a follow if they're processed later.
- An `Undo` that names a `Follow` isn't affected by another `Follow` that arrived after the named one, and an `Undo` of an
  embedded `Follow` with no `id` covers every `Follow` that arrived before it.
- A `Follow` that arrived before the one behind an existing follow doesn't replace it, and gets no `Accept`.
- A newer `Follow` replaces an older one's `Accept` if that hasn't been sent yet, and an `Undo` cancels the `Accept` of the
  follow it removes, if that hasn't been sent yet.

An `Undo` that names a `Follow` before it arrives still counts, for about 34 days, and only stops that actor's `Follow` with
that `id`. If an embedded `Follow` has no `id`, then a `Follow` that arrives after it can't be told apart from a new request,
so it creates a follow.

Some servers use the same `id` for each `Follow` between two actors, and for each `Undo` of it, so a `Follow` or `Undo` that
repeats an `id` from the same actor is processed again, rather than dropped. A repeated `Follow` is a new request, so it stands
if it arrived after any `Undo` of it.

### Notable differences

- Local users can't follow other actors, or approve followers by hand. Actor documents don't include
  `manuallyApprovesFollowers`.
- `Reject` is never sent.
- A follower stays stored when its actor is deleted, because `Delete` isn't acted on.
- Actor documents advertise an `outbox`, but it isn't implemented yet.
- Actor documents don't include `followers` or `following` collections.
- `Note` objects aren't addressed to followers, because actors don't have a followers collection yet.
- `Note` objects for replies don't address the parent's author or include a `Mention` tag.
- A reply's `inReplyTo` is left out if its parent is private, direct, or deleted, so it looks like it isn't a reply.
- Deleted statuses get a 404, not a `Tombstone`.
- Inboxes can't be read, so a `GET` gets a 403.
- The `outbox` that the instance actor advertises isn't implemented yet.
- Fetching actors and objects from this server doesn't need a signature, and any signature on those requests is ignored.

### HTTP Signatures

The server supports [draft-cavage-http-signatures-12](https://datatracker.ietf.org/doc/html/draft-cavage-http-signatures-12).
[RFC 9421](https://www.rfc-editor.org/rfc/rfc9421) isn't implemented. Only inboxes check signatures. The only requests this
server makes are fetches of keys and actor documents, and deliveries of `Accept`.

- **Signing:** Requests are signed with `rsa-sha256`, and cover `(request-target)`, `host`, and `date`. Requests with a
  method other than `GET` or `HEAD`, or with a body, also cover `digest`, sent as a SHA-256 `Digest` header.
- **Verifying:** Signatures have to cover `(request-target)`, `host`, and either `date` or `(created)`, plus `digest` in
  the same cases as above. `rsa-sha256` and `hs2019` are both accepted, and both are checked as RSA with SHA-256.
  Signatures last 5 minutes unless they say otherwise, and at most 12 hours, with up to 1 hour of clock difference allowed.
- **Fetching keys:** Fetches are signed as the [instance actor](#instance-actor).

## WebFinger

`/.well-known/webfinger` accepts `acct:{username}@{domain}` and actor URLs as the `resource`, including
`acct:instance.actor@{domain}` and `/actor` for the instance actor, whose profile page link is the front page. Responses
are served as `application/jrd+json` or `application/json`, include a `self` link to the actor document and a
`http://webfinger.net/rel/profile-page` link to the user's profile page, and can be read from any origin through CORS.

## NodeInfo

`/.well-known/nodeinfo` links to NodeInfo documents at `/nodeinfo/2.1` and `/nodeinfo/2.0`, and all of these can be read
from any origin through CORS. The documents match, except that only 2.1 includes the software's repository. They report
`activitypub` as the protocol, even though federation isn't complete yet. Their usage statistics include the total number
of local users and `localPosts`, which counts replies but not direct messages. Active user counts and `localComments`
aren't reported.

## Additional documentation

- [README](README.md)
