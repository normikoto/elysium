# Federation

Elysium is an early work in progress and can't fully federate yet. Other servers can discover its users, follow them, and
receive their new and deleted statuses, but its users can't follow anyone. This file only describes what's implemented today.

## Supported federation protocols and standards

- [ActivityPub](https://www.w3.org/TR/activitypub/) (Server-to-Server): fetching actors and `Note` objects, accepting
  activities at an inbox, receiving `Follow` and `Undo` of a `Follow`, and sending `Accept`, `Create`, `Update`, and `Delete`
- [WebFinger](https://webfinger.net/)
- [NodeInfo](https://nodeinfo.diaspora.software/) 2.0 and 2.1
- [HTTP Signatures](https://datatracker.ietf.org/doc/html/draft-cavage-http-signatures-12) (draft-cavage), with RSA and SHA-256

## Supported FEPs

- [FEP-67ff: FEDERATION.md](https://codeberg.org/fediverse/fep/src/branch/main/fep/67ff/fep-67ff.md)
- [FEP-f1d5: NodeInfo in Fediverse Software](https://codeberg.org/fediverse/fep/src/branch/main/fep/f1d5/fep-f1d5.md)

## ActivityPub

### Actors

Local users are `Person` actors at `/users/{username}`. Requests that ask for `application/activity+json` or
`application/ld+json` get the actor document, and browsers get the user's profile page at the same URL. Each actor has an RSA
key, identified as `{actor-uri}#main-key`, and a `followers` collection that only gives its size.

Usernames are 1 to 30 letters, digits, or underscores, and are matched case-insensitively.

The server itself is an `Application` actor at `/actor`, which can be found through WebFinger as `instance.actor`. It signs
requests that no user is behind, such as fetching other servers' keys, and can't be followed.

### Statuses

Public and unlisted statuses are `Note` objects at `/users/{username}/statuses/{id}`, which is also the URL of their web
page. Private and direct statuses can't be fetched. A deleted public or unlisted status answers with a `410` and a
`Tombstone`.

When a local user posts a public, unlisted, or followers-only status, it's sent as a `Create` to their followers on other
servers, using shared inboxes where possible. Deleting one sends a `Delete` the same way. Direct statuses aren't sent, and
neither are statuses from before someone followed. A followers-only status, and its `Delete`, only go to those who were
already following when it was posted.

### Inboxes and follows

Each local user has an inbox, and there's a shared inbox at `/inbox`. Deliveries must be signed by the activity's actor, so
forwarded activities are dropped. Activities are processed in the background, so an inbox's `202` doesn't say whether one
worked. Only `Follow` and `Undo` of a `Follow` are acted on, and other types are ignored.

A `Follow` of a local user is always accepted, because accounts can't approve followers by hand yet, and gets an `Accept`.
An `Undo` of the `Follow` removes it.

Addresses that make this server fetch too many keys get a `429`. This limit is always on, and the
[rate limit setting](docs/accounts.md#rate-limits) doesn't turn it off.

### Suspended and deleted accounts

When an admin [suspends or deletes a local account](docs/accounts.md#suspending-and-deleting-accounts), its followers'
servers are told:

- **Suspended:** They get an `Update` of the actor with `suspended: true`, and another without it when the suspension ends.
  The actor document is still served, but its statuses and followers get a `403`, and its pages a `404`.
- **Deleted:** They get a `Delete` of the actor. The actor, its statuses, and WebFinger answer with a `410` from then on.
  No `Delete` is sent for each status.

Suspending an account on another server refuses its activities, stops sending to it, and removes its follows. Its server
isn't told.

### Domain blocks

When an admin [blocks a domain](docs/accounts.md#domain-blocks), which also covers its subdomains, deliveries signed by its
keys get a `403`, nothing is fetched from it or sent to it, and its follows are removed. A block doesn't stop its users from
reading public pages and documents, which need no signature, and doesn't cover a server reached by IP address. Anyone can
find out whether a domain is blocked, from whether its deliveries get a `403`, as with Mastodon and GoToSocial.

### Notable differences

- Local users can't follow anyone, or approve followers by hand. `Reject` is never sent.
- `Delete` of a remote actor isn't acted on, so its follows stay stored.
- Actor documents advertise an `outbox`, but it isn't implemented yet, and there's no `following` collection.
- Replies don't include a `Mention` tag.
- Followers-only statuses can't be fetched, even by a follower.
- Fetching from this server never needs a signature.
- [RFC 9421](https://www.rfc-editor.org/rfc/rfc9421) signatures and Linked Data Signatures aren't supported.

## WebFinger

`/.well-known/webfinger` accepts `acct:{username}@{domain}` and actor URLs, and links to the actor document and profile page.

## NodeInfo

`/.well-known/nodeinfo` links to `/nodeinfo/2.1` and `/nodeinfo/2.0`. They report the number of local users and posts, and
`activitypub` as the protocol, even though federation isn't complete yet.

## Additional documentation

- [README](README.md)
