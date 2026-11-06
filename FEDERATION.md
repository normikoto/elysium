# Federation

Elysium is an early work in progress and can't fully federate yet. Other servers can discover its users, deliver activities
to them, follow them, and receive their new and deleted statuses, but its users can't follow anyone. This file only describes
what's implemented today.

## Supported federation protocols and standards

- [ActivityPub](https://www.w3.org/TR/activitypub/) (Server-to-Server): fetching actors and `Note` objects, accepting
  activities at an inbox, receiving `Follow` and `Undo` of a `Follow`, and sending `Accept`, `Create`, `Update`, and `Delete`
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

An actor's `followers` is an `OrderedCollection` at `/users/{username}/followers`, with its `totalItems` and no items. It's
served as `application/activity+json` or `application/ld+json` with any profile, including for `*/*` or no `Accept` header,
and any origin can read it through CORS. There's no web page at this URL, so a request that only accepts HTML gets a 406.

Usernames are 1 to 30 letters, digits, or underscores, and are matched case-insensitively. A deleted account's username
can't be used again, because other servers can still know its URI.

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
get the page, and any origin can read both through CORS. Private and direct statuses get a 404.

Once a public or unlisted status is deleted, a request for the object gets a `410` with a `Tombstone`, and a request for the
page gets a 404. A deleted private or direct status still gets a 404, the same as one that never existed.

```json
{
  "@context": "https://www.w3.org/ns/activitystreams",
  "id": "https://elysium.example/users/alice/statuses/019a0000-0000-7000-8000-000000000000",
  "type": "Tombstone",
  "formerType": "Note",
  "deleted": "2026-01-02T00:00:00Z"
}
```

A `Note` has `id`, `attributedTo`, `url`, `published`, `to`, `cc`, `content`, and `sensitive`, which is defined in its
`@context` as `as:sensitive`. It can also have:

- `summary`: the content warning, as plain text rather than HTML, which matches Mastodon.
- `inReplyTo`: the parent status.
- `contentMap`: the `content` under its language, if one is set.

Statuses are addressed like this, where `followers` is the author's followers collection:

| Visibility     | `to`      | `cc`      |
|----------------|-----------|-----------|
| Public         | public    | followers |
| Unlisted       | followers | public    |
| Followers-only | followers |           |

A reply also has the parent's author in `cc`, if its `inReplyTo` is shown and that's someone else. There's no `Mention` tag.

A reply's `inReplyTo` is shown if the parent is public or unlisted, or if a followers-only status replies to its author's own
followers-only status.

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
| `403`  | The activity's `id` is a valid URI, but not an HTTPS URL on the same host and port as its `actor`, or the signature's key is on a [blocked domain](#domain-blocks) |
| `404`  | The user doesn't exist                                                                                       |
| `413`  | The body is too large                                                                                        |
| `415`  | The `Content-Type` isn't one of those above                                                                  |
| `429`  | This address has caused too many key fetches, or the signer's server has delivered too much, and `Retry-After` says when to try again |
| `503`  | The sender's key can't be fetched right now                                                                  |

Key fetches are limited by address: requests from one address can start 30 a minute, with 4 running at once. A request only
counts if it starts a fetch, so one signed with a key this server already has isn't limited, and neither is one waiting on
a fetch that's already running. IPv6 addresses are counted by their /64 network.

Each server can also deliver at most 120 activities a minute, with bodies adding up to at most 20 MiB an hour, by default.
Admins can change these, and remove them for servers they trust, as described in
[Configuration](docs/configuration.md#inbox-limits). A server is the registrable domain of the signing key's actor, from the
[Public Suffix List](https://publicsuffix.org/), so `a.example.com` and `b.example.com` share one limit, but `a.github.io`
and `b.github.io` don't. An IP address counts as itself. Only activities that pass every other check count, including
repeats of an `id` that's already been accepted, and one that gets a `429` doesn't count. Each minute and hour is a fixed
window, so a burst at the end of one and the start of the next can reach twice the limit. `Retry-After` gives the longer of
the two waits, so it can be later than needed. Counts are kept in memory, and restarting resets them.

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
is stored. If an older copy is stored, it's used when the fetch fails, unless the server answers with a `404` or `410`. A
`Follow` is refused, and not tried again, unless the document:

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

An `Undo` of an embedded `Follow` whose `id` isn't known here removes any follow between its actor and its `object` that arrived
before it, because a server can make a new `id`. An `Undo` that only gives an `id` that isn't known has no other effect.

An `Undo` that names a `Follow` before it arrives still counts, for about 34 days, and only stops that actor's `Follow` with
that `id`. If an embedded `Follow` has no `id`, then a `Follow` that arrives after it can't be told apart from a new request,
so it creates a follow.

A `Follow` or `Undo` that repeats an `id` from the same actor is processed again, rather than dropped. Misskey uses the same
`id` for each `Undo` between two actors, so a repeated `Undo` can undo a new follow. A repeated `Follow` gets another `Accept`,
unless an `Undo` covers it. It's then treated as a retry, so a new request to follow needs a new `id`.

### Statuses

When a local user posts a public, unlisted, or followers-only status, it's sent to their followers on other servers, signed as
the user. Each server's shared inbox is used for its followers that have one, so that they share one delivery, and other
followers' `inbox` is used otherwise. Direct statuses aren't sent, since they have no audience without mentions. Followers are
worked out shortly after posting, and statuses from before someone followed aren't sent. A
followers-only status only goes to those who were already following when it was posted.

```json
{
  "@context": ["https://www.w3.org/ns/activitystreams", {"sensitive": "as:sensitive"}],
  "id": "https://elysium.example/users/alice/statuses/019a0000-0000-7000-8000-000000000000#create",
  "type": "Create",
  "actor": "https://elysium.example/users/alice",
  "published": "2026-01-01T00:00:00Z",
  "to": ["https://www.w3.org/ns/activitystreams#Public"],
  "cc": ["https://elysium.example/users/alice/followers"],
  "object": {
    "id": "https://elysium.example/users/alice/statuses/019a0000-0000-7000-8000-000000000000",
    "type": "Note",
    "attributedTo": "https://elysium.example/users/alice",
    "url": "https://elysium.example/users/alice/statuses/019a0000-0000-7000-8000-000000000000",
    "published": "2026-01-01T00:00:00Z",
    "to": ["https://www.w3.org/ns/activitystreams#Public"],
    "cc": ["https://elysium.example/users/alice/followers"],
    "content": "<p>Hello</p>",
    "sensitive": false
  }
}
```

The `object` is the same `Note` that's described in [Objects](#objects). The `id` can't be fetched as an activity, and gives
the `Note` instead, if the `Note` can be fetched. In rare cases, such as after a failure, a `Create` can be sent again with the
same `id`.

When a local user deletes a public, unlisted, or followers-only status, any delivery of its `Create` that hasn't started is
cancelled, and a `Delete` is sent to their followers on other servers, the same way as a `Create`. It's addressed like the
`Create`, except that it doesn't address the author of a parent status. Deleting a direct status sends nothing. The `Delete` of
a followers-only status only goes to followers who were already following when it was posted, so that it can't tell anyone else
that the status existed.

A `Delete` doesn't overtake a `Create` that's already being sent: it waits until that request to the same server has ended,
which takes at most 30 seconds. This server can only control when each is sent, so a server that handles activities out of order
could still process them the other way around.

```json
{
  "@context": "https://www.w3.org/ns/activitystreams",
  "id": "https://elysium.example/users/alice/statuses/019a0000-0000-7000-8000-000000000000#delete",
  "type": "Delete",
  "actor": "https://elysium.example/users/alice",
  "to": ["https://www.w3.org/ns/activitystreams#Public"],
  "cc": ["https://elysium.example/users/alice/followers"],
  "object": {
    "id": "https://elysium.example/users/alice/statuses/019a0000-0000-7000-8000-000000000000",
    "type": "Tombstone",
    "formerType": "Note"
  }
}
```

A `Delete` can be sent for a status that a server never received, and in rare cases, such as after a failure, it can be sent
again with the same `id`.

### Notable differences

- Local users can't follow other actors, or approve followers by hand. Actor documents don't include
  `manuallyApprovesFollowers`.
- `Reject` is never sent.
- A follower stays stored when its actor is deleted, because `Delete` isn't acted on.
- Actor documents advertise an `outbox`, but it isn't implemented yet.
- Actor documents don't include a `following` collection, and `followers` doesn't list its items.
- `Note` objects for replies don't include a `Mention` tag.
- A reply's `inReplyTo` is left out if its parent is deleted or can't be shown, so it looks like it isn't a reply.
- Followers-only statuses are only sent, and can't be fetched, even by a follower.
- A `Delete` goes to whoever follows the author when it's deleted, so a server whose only follower has since unfollowed keeps
  its copy. For a followers-only status, that includes a follower who unfollowed and then followed again after it was posted.
- Inboxes can't be read, so a `GET` gets a 403.
- The `outbox` that the instance actor advertises isn't implemented yet.
- Fetching actors and objects from this server doesn't need a signature, and any signature on those requests is ignored.

### Suspended and deleted accounts

Admins can [suspend or delete an account](docs/accounts.md#suspending-and-deleting-accounts). Other servers get these answers
for a local account:

| Request                 | Suspended                                       | Deleted                                         |
|-------------------------|-------------------------------------------------|-------------------------------------------------|
| Actor document          | `200`, with `suspended: true` and the same key  | `410`, with a `Tombstone` (`formerType: Person`) |
| `Note`                  | `403`                                           | `410`, with a `Tombstone` (`formerType: Note`)  |
| Followers collection    | `403`                                           | `410`                                           |
| WebFinger               | `200`                                           | `410`                                           |
| Personal inbox          | As usual                                        | `410`                                           |
| Profile and status page | `404`                                           | `404`                                           |

`suspended` is `toot:suspended` (`http://joinmastodon.org/ns#suspended`), and the `@context` only defines it on a document
that has it. A suspension isn't a `404` or `410`, so that other servers don't take it as the actor or its statuses being gone
for good. A deleted account's `Note`s all get the same answer, whatever their ID, so this doesn't say which ones existed.

- **Suspending a local account:** Its followers' servers get an `Update` of the actor with `suspended: true`, and an `Update`
  without it if the suspension ends, each signed by the actor, with its own ID. They aren't ordered, so if a server is down
  through a quick suspension and its end, the older `Update` can arrive last, and that server has it wrong until it next
  refreshes the actor. Followers are kept. A `Create` still queued is dropped, and isn't sent if the suspension ends. Other
  activities still queued, such as a status's `Delete` or a follow's `Accept`, wait and are sent within 10 minutes of the
  suspension ending, unless that takes more than 33 days. A `Follow` of a suspended account gets no answer, and an `Undo`
  of one works as usual.
- **Deleting a local account:** Its followers' servers get a `Delete` signed by the actor, whose `object` is the actor's URI, with
  the ID `{actor-uri}#delete`. No `Delete` is sent for each status. The actor document answers `410` straight away, so a server has
  to verify the `Delete` with a key it already has. Deliveries can be retried for up to 34 days.
  Who's told comes from who follows the account then, so a server whose followers have all left keeps its copy.
- **Suspending a remote actor:** A delivery signed with its key gets a `403`, and its activities are refused, including any
  still queued. Its document isn't fetched or refreshed. Its follows are removed, and aren't restored if the suspension ends, so
  it would have to follow again. Nothing more is sent to its personal inbox, but a shared inbox still gets activities for
  other followers. Its own server isn't told. Only an actor that's already stored can be suspended.

### Domain blocks

Admins can [block a domain](docs/accounts.md#domain-blocks), which also covers its subdomains and every port. Domains are
compared in lowercase ASCII, without a trailing dot. A block is only ever a full one, with no lesser level.

- **Inbound:** A delivery signed with a key on a blocked domain gets a `403`, before any key is fetched, so it doesn't use up
  any limits. This also applies to keys this server has already cached. A signature that's malformed or out of date still
  gets its usual `400` or `401`, because those are checked first. Activities that were already queued are dropped without
  being processed.
- **Fetching:** Keys and actors are never fetched from a blocked domain.
- **Delivery:** Nothing is sent to an inbox on a blocked domain. Deliveries that were already queued finish without being
  sent, and that doesn't count as the server failing.
- **Existing data:** Follows to and from its actors are removed. Its actors are kept.
- **Removing a block:** Nothing that was removed comes back. A follower has to ask again, and what was dropped while it was
  blocked isn't delivered. Following again counts as a new follow, so that server isn't sent a `Delete` for a followers-only
  status posted before then, and it keeps any copy it has.

A block doesn't stop a server's users from reading this server's public pages and documents, which need no signature, or
recall a delivery that's already being sent. It only covers names, so it doesn't apply to a server that moves to an unrelated
domain, or that's reached by its IP address, and IP addresses can't be blocked.

The list of blocks isn't shown anywhere, but anyone can find out whether a domain is on it. The `403` comes before the
signature is checked, so a request naming a key on that domain gets one whether or not it really came from there. Mastodon and
GoToSocial answer the same way.

### HTTP Signatures

The server supports [draft-cavage-http-signatures-12](https://datatracker.ietf.org/doc/html/draft-cavage-http-signatures-12).
[RFC 9421](https://www.rfc-editor.org/rfc/rfc9421) isn't implemented. Only inboxes check signatures. The only requests this
server makes are fetches of keys and actor documents, and deliveries of `Accept`, `Create`, `Update`, and `Delete`.

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
