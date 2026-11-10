# Federation

Elysium is an early work in progress, and this file only describes what's implemented today. Other servers can find and
follow its users, and fetch their actors, statuses, outboxes, and collections. Its users can follow accounts on other servers,
whose recent posts are then fetched from their outbox, and like and boost their posts. Their statuses and edits to them, edits to their profiles, and suspensions and deletions of their accounts
are sent to their followers. Statuses from other servers are stored, along with their edits and deletions, when they're for a local user,
reply to a status that's stored here, or are boosted by an actor that a local user follows. Likes and boosts of local
statuses are counted. Their images, video, and audio are sent and read as attachments, and other servers' are
downloaded and served from here for a while. Polls and other activities aren't supported yet.

## Supported federation protocols and standards

- [ActivityPub](https://www.w3.org/TR/activitypub/) (Server-to-Server): receiving `Follow`, `Like`, and `Announce` and their
  `Undo`, `Accept` and `Reject` of a `Follow`, `Create` of a `Note`, `Update` of an actor or a `Note`, and `Delete`, and
  sending `Follow`, `Like`, and `Announce` and their `Undo`, `Accept` and `Reject` of a `Follow`, `Create`, `Update`, and
  `Delete`
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

Each actor document includes `publicKey` with an RSA 2048 key, identified as `{actor-uri}#main-key`, and
`endpoints.sharedInbox`, which is the [shared inbox](#inboxes) at `/inbox`.

An actor document also has the user's profile, as Mastodon sends it, and leaves out each part that's empty:

- `name`, the display name, which is plain text of up to 30 characters.
- `summary`, the bio, as HTML: `<p>` paragraphs and `<br>` line breaks, with URLs as `<a>` links and hashtags in
  [the same markup as a status's](#objects). Everything else is escaped.
- `tag`, a `Hashtag` for each hashtag in the bio, as a `Note` has them.
- `icon` and `image`, the avatar and header, as `Image`s with a `url` and a `mediaType` of `image/webp`. They're always WebP,
  and can be animated. An avatar is 400 × 400, and a header 1500 × 500.
- `attachment`, the fields, as `PropertyValue`s (`schema:PropertyValue`), each with a `name` and a `value` that's HTML. A
  URL in a value is a link with `rel="me nofollow noopener noreferrer"`. Whether this server has verified the link isn't
  sent, since each server checks for itself.
- `published`, when the account was created, and `updated`, when the profile last changed, both to the second.

`manuallyApprovesFollowers` is always included, and is true when the user [approves followers by hand](#follows).

The `@context` adds `schema` (`http://schema.org#`), `PropertyValue`, and `value` (`schema:value`) for the fields,
`Hashtag` (`as:Hashtag`), and `manuallyApprovesFollowers` (`as:manuallyApprovesFollowers`).

When a user changes their profile, or whether they approve followers by hand, their followers' servers get an `Update` of the actor, signed by the actor, with the ID
`{actor-uri}#profile/{milliseconds}`. It's addressed to the public, with the followers collection in `cc`, and its
`object` is the actor document, without a `@context` of its own, as it is when it's sent. A save that changes nothing
sends nothing, and neither does verifying a field. These aren't ordered, so a server that's been down can get an older
`Update` last, and the actor's `updated` says which is newer.

When a field's value is only an `https` URL, this server fetches that page when the field is saved, to see whether it links
back to the actor with `rel="me"`. These requests aren't signed, ask for `text/html`, follow up to 3 redirects, and only
reach public addresses over HTTPS. A link back is an `<a>` or `<link>` whose `rel` includes `me`, and whose `href`
resolves to the actor's URI, ignoring a trailing slash.

An actor's `followers` and `following` are `OrderedCollection`s at `/users/{username}/followers` and
`/users/{username}/following`. They're served as `application/activity+json` or `application/ld+json` with any profile,
including for `*/*` or no `Accept` header, and any origin can read them through CORS. There's no web page at these URLs, so a
request that only accepts HTML gets a 406.

Each collection has a `totalItems` and a `first` page, at `?page=true`. A page is an `OrderedCollectionPage` with `partOf`,
up to 40 `orderedItems`, which are actor URIs, newest follow first, and a `next` page if there are more, at
`?page=true&max_id={cursor}`. Only accepted follows are counted or listed, and suspended actors are left out. A user can hide
who's in their collections, and then a collection, or any page of it, only has its `totalItems`, with no `first` or items.

An actor's `outbox` is an `OrderedCollection` at `/users/{username}/outbox`, served like followers and following, with the
same paging, 403, and 410. It lists the user's public and unlisted posts and boosts, newest first, and `totalItems` counts
them. A post is the `Create` that was [sent](#statuses) for it, embedding the `Note` as it's served now, and a boost is its
[`Announce`](#boosts). Followers-only and direct posts, followers-only boosts, and boosts of posts that can't be shown are left
out, so a page can have fewer than 40 items and still have a `next`. Any signature is ignored. The
[instance actor](#instance-actor)'s outbox, at `/actor/outbox`, is always empty.

Usernames are 1 to 30 letters, digits, or underscores, and are matched case-insensitively. A deleted account's username
can't be used again, because other servers can still know its URI.

### Instance actor

The server itself is an `Application` actor at `/actor`, with the `preferredUsername` `instance.actor`. It's for signing
requests that no user is behind, which so far means fetching keys and actor documents. Its document is served as
`application/activity+json` or `application/ld+json` with any profile, and any origin can read it through CORS. It never
requires a signature. There's no web page at this URL, so its `url` links to the front page. Its key is identified as
`{actor-uri}#main-key`, its `endpoints.sharedInbox` is `/inbox`, and it isn't counted as a user. It can't be followed, so a `Follow` of it is ignored, with no
`Reject`.

### Objects

Statuses are `Note` objects at `/users/{username}/statuses/{id}`, which is also the URL of their web page. These are
served the same way as actor documents: the same `Accept` types get the object, requests that accept HTML get the page,
and any origin can read both through CORS. Public and unlisted statuses are served to anyone.

A fetch of a `Note` doesn't need a signature, but one is checked if it's sent, and the request is answered as the actor
that owns the signing key:

- A followers-only status is served to an actor that was already following its author when it was posted, which is who
  its `Create` went to, and to the actors in its `Mention` tags, or in them before an edit.
- A direct status is served to the actors in its `Mention` tags, or in them before an edit.
- Only the signer counts. An instance actor, or any other actor, isn't served a status because other actors on its
  server can see it. This matches Mastodon. Misskey's lookups, and Mastodon's searches, are signed as their instance
  actor, so they can't find a followers-only or direct status that wasn't delivered to them.
- Everyone else gets a 404, the same as for a status that never existed. A signature that can't be accepted, for any
  reason, including a key on a [blocked domain](#domain-blocks), a suspended signer, or a key that can't be fetched right
  now, gets the answer an unsigned request would. Key fetches count toward the same [limits](#inboxes) as for inboxes.

Answers vary by `Signature`, and a followers-only or direct `Note` has `Cache-Control: no-store, private`. Browsers can't
send a `Signature` header to these URLs from another origin, so what other sites can read through CORS is always the
unsigned answer.

Once a public or unlisted status is deleted, a request for the object gets a `410` with a `Tombstone`, and a request for the
page gets a 404. A deleted followers-only or direct status gets a 404 from then on, even with a signature, the same as one
that never existed.

```json
{
  "@context": "https://www.w3.org/ns/activitystreams",
  "id": "https://elysium.example/users/alice/statuses/019a0000-0000-7000-8000-000000000000",
  "type": "Tombstone",
  "formerType": "Note",
  "deleted": "2026-01-02T00:00:00Z"
}
```

A `Note` has `id`, `attributedTo`, `url`, `published`, `to`, `cc`, `content`, and `sensitive`. Its `@context` defines
`sensitive` as `as:sensitive`, `Hashtag` as `as:Hashtag`, and `blurhash` and `focalPoint` in Mastodon's `toot`
namespace, as Mastodon's does. It can also have:

- `likes`: the post's likes collection, at `{note-uri}/likes`.
- `shares`: the post's [boosts](#boosts) collection, at `{note-uri}/shares`.
- `updated`: when it was last edited, if it has been.
- `summary`: the content warning, as plain text rather than HTML, which matches Mastodon.
- `inReplyTo`: the parent status.
- `contentMap`: the `content` under its language, if one is set.
- `tag`: a `Mention` for each account the status mentions, with the actor's ID as `href` and its full address, such as
  `@bob@example.com`, as `name`. Mentioned accounts that have since been suspended or deleted are left out. Then a
  `Hashtag` for each of its hashtags, up to 100, with `#` and the normalized name as `name`, such as `#café`, and this
  server's page of public statuses with the tag, `/tags/{name}`, as `href`. A normalized name is lowercase, in
  Unicode's NFKC form. That page is HTML only, and isn't an ActivityPub collection.
- `attachment`: a `Document` for each of its images, video, and audio, up to 4, in order, as Mastodon sends them. Each
  has `mediaType`, `url`, and, if it has them, `name` (the description, as plain text), `width` and `height` (images
  only), and `focalPoint`. Images are always `image/webp`. Video and audio are the files as they were uploaded, so their
  `mediaType` is one of `video/mp4`, `video/quicktime`, `video/webm`, `audio/mp4`, `audio/mpeg`, `audio/ogg`,
  `audio/flac`, or `audio/wav`. There's no `blurhash`. A status can have attachments and empty `content`.

Statuses are addressed like this, where `followers` is the author's followers collection and `mentioned` the actors in its
`Mention` tags:

| Visibility     | `to`      | `cc`                 |
|----------------|-----------|----------------------|
| Public         | public    | followers, mentioned |
| Unlisted       | followers | public, mentioned    |
| Followers-only | followers | mentioned            |
| Direct         | mentioned |                      |

A reply that isn't direct also has the parent's author in `cc`, if its `inReplyTo` is shown and that's someone else. A
direct reply only addresses its parent's author if it mentions them, as on Mastodon.

In the `content`, a mention is a link to the actor's ID, in Mastodon's markup:
`<span class="h-card" translate="no"><a href="…" class="u-url mention" title="@bob@example.com">@<span>bob</span></a></span>`.
Only the username is shown, also for an actor on another server. A hashtag is a link to its `href`, with the name as it
was written: `<a href="…" class="mention hashtag" rel="tag">#<span>Café</span></a>`.

A reply's `inReplyTo` is shown if the parent is public or unlisted, or if a followers-only status replies to its author's own
followers-only status. A direct reply also shows a followers-only or direct parent if it mentions the parent's author, so
that direct conversations stay threaded.

### Inboxes

Each local user has an inbox at `/users/{username}/inbox`, the instance actor has one at `/actor/inbox`, and there's a
shared inbox at `/inbox`, which every actor document gives as its `endpoints.sharedInbox`. They all work the same way, since
who an activity is for comes from the activity rather than the inbox it was sent to. They accept a `POST` with a
`Content-Type` of
`application/activity+json`, or `application/ld+json` with any profile, and a body of up to 1 MiB. Activities are read as
plain JSON, without a JSON-LD processor, and no `@context` is ever fetched. Inboxes can't be read, so a `GET` gets a 403.

| Status | Meaning                                                                                                      |
|--------|--------------------------------------------------------------------------------------------------------------|
| `202`  | The activity is queued, it's a repeat of one with the same `id`, it was [forwarded](#forwarded-activities) and will be fetched from its origin, or it was dropped |
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
and `b.github.io` don't. An IP address counts as itself, and an IPv6 address by its /64 network. Only activities that pass every other check count, including
repeats of an `id` that's already been accepted, and one that gets a `429` doesn't count. Each minute and hour is a fixed
window, so a burst at the end of one and the start of the next can reach twice the limit. `Retry-After` says when the limit
that was reached has room again. Counts are kept in memory, and restarting resets them.

### Activities

Activities that are accepted are stored, and processed later by a background job, so an inbox's answer doesn't say whether
one worked. Activities are processed in no particular order. `Follow`, `Like`, `Announce`, their `Undo`s, `Accept` and
`Reject` of a `Follow`, `Create` of a `Note`, `Update` of an actor or a `Note`, and `Delete` are acted on, and other types are
ignored.

An activity is accepted as it was sent if its `actor`, which can be an array of one, is the actor that signed the request.
One with several actors is answered with a `202` and dropped. Linked Data Signatures aren't verified.

#### Forwarded activities

An activity signed by someone other than its `actor`, such as a reply that Mastodon forwards to the followers of the
account being replied to, is answered with a `202`, and fetched later from its `id`, signed as the
[instance actor](#instance-actor). Nothing from the forwarded copy is kept: the `id` isn't stored until the origin answers,
so a forwarded activity can't stop the real one from being accepted.

It's dropped, still with a `202`, unless its `id` is an HTTPS URL on the same host and port as its `actor`, on another
server than this one, that isn't [blocked](#domain-blocks). Each counts against the signer's server's
[limits](#inboxes), because that's the server causing the fetch, and a `429` can be sent for one. A repeat of an `id` that's
already been accepted, or already queued, isn't fetched again. It counts as arriving when the first forwarded copy did,
not when it's fetched, so an `Undo` that arrives in between still covers a forwarded `Follow`.

What the origin serves is only accepted if it's an activity whose `id` is exactly the one fetched, with one `actor` on the
same host and port. That actor is the one believed, even if the forwarded copy named another. A `404`, `410`, or another
refusal drops the activity, and a server error or timeout is tried again later. This means an activity that its origin
doesn't serve is lost when it's forwarded, except for a `Delete`, such as Mastodon's, whose `id` has a fragment. Its `object`
is [checked with its own server](#receiving-deletes) instead, if it's on the same host and port as the `actor`.

The sender chooses where these fetches go, so each host gets at most 60 of them a minute, with bursts of up to 60, counting
the fetches of forwarded activities, of objects [checked for a `Delete`](#receiving-deletes), and of
[boosted statuses](#boosts) together. A fetch over that waits for its turn, and is dropped if that's more than an hour away.
This is counted by each of this server's nodes separately. No more than 4 requests to one host are made at once for fetches of any kind, and a fetch that would be over
that is tried again later.

#### Reading activities

Activities are read as plain JSON, so properties are matched by their usual names, such as `content` or `sensitive`, and
unknown ones are ignored. A server that renames them with its own `@context` is misread. These forms are treated the same:

- `to`, `cc`, `bto`, `bcc`, `audience`, `attributedTo`, `type`, and `tag` can be one value or an array, and each address
  can be an ID or an object with an `id`.
- `actor` and `object` can be an ID, an embedded object, or an array of one of those.
- The public collection can be `https://www.w3.org/ns/activitystreams#Public`, `as:Public`, or `Public`.
- `content` and `summary` can be sent only in `contentMap` and `summaryMap`. A value with the language `und` is used first,
  and then the first one given.

Who an activity is for is worked out from its addressing, along with that of the `object` embedded in a `Create` or
`Update`: the local users named in any
of the properties above, local users who follow its actor when the actor's `followers` collection is addressed, and the
`object` of a `Follow`. Only the actor's own `followers` collection is recognized, as given in its actor document, and
collections of other actors aren't fetched.

### Follows

A `Follow` whose `object` is a local user is accepted straight away, unless the user approves followers by hand, when it
waits for them. Who's following comes from the signature, and not from the activity's body. The `object` can be an ID, an embedded object with an
`id`, or an array of one of those. A `Follow` of anything else is ignored.

The follower's document is fetched, signed as the [instance actor](#instance-actor), unless a copy from the last 24 hours
is stored. If an older copy is stored, it's used when the fetch fails, unless the server answers with a `404` or `410`. A
`Follow` is refused, and not tried again, unless the document:

- Has an `id` matching the signer, and an actor `type`: `Application`, `Group`, `Organization`, `Person`, or `Service`.
- Has a `preferredUsername` of up to 255 characters, with no control characters. Other actors on that server can have the
  same one, because an actor's URI is what identifies it.
- Has an `inbox` that's an HTTPS URL on the same host and port as the actor, with no user info. This stops a document from
  aiming this server's requests at another server. An `endpoints.sharedInbox` or `followers` that isn't one is left out,
  without refusing the actor.
- Is on a different domain from this server.

Each `Follow` that's accepted gets an `Accept`, signed as the user being followed, and sent to the follower's `inbox`. This
includes another `Follow` from someone who's already following, since their server may have lost track, whether or not it
has a new `id`, and whether or not the user approves followers by hand. The `Accept`'s `id` is
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

A `Follow` that waits for the user gets no answer until they decide, however often it's repeated, and the follower doesn't
count as one meanwhile: it's left out of the followers collection, gets no activities sent to followers, and can't fetch
followers-only statuses. A newer `Follow` from the same actor while it's waiting takes its place, and the answer names that
one. If the user approves it, the follower gets an `Accept` as above. Followers-only statuses posted before then aren't
served to it. If the user refuses it, the follower gets a `Reject`, with the `id` `{actor-uri}#rejects/follows/{hash}` and the
`Follow` as its `object`, in the same form as an `Accept`:

```json
{
  "@context": "https://www.w3.org/ns/activitystreams",
  "id": "https://elysium.example/users/alice#rejects/follows/2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
  "type": "Reject",
  "actor": "https://elysium.example/users/alice",
  "object": {
    "id": "https://remote.example/activities/1",
    "type": "Follow",
    "actor": "https://remote.example/users/bob",
    "object": "https://elysium.example/users/alice"
  }
}
```

A repeat of a refused `Follow`'s `id` is ignored for about 34 days, as for one that's been undone, and a `Follow` with a
new `id` is a new request. When the user stops approving followers by hand, every request that's waiting is approved.
Turning it on doesn't change who's already following.

An `Undo` removes a follow, or a request that's waiting, if its `object` names the `Follow` that created it, by `id`, or
as an embedded `Follow` with an `id`. An embedded `Follow` with no `id` removes the follow between its actor and its
`object`. Nothing happens if:

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

### Following other actors

A local user can follow an actor on another server, which they find by its `username@domain` or URL. This server looks up the
address with an unsigned WebFinger request to `https://{domain}/.well-known/webfinger`, and takes the `self` link of type
`application/activity+json`, or `application/ld+json` with the ActivityStreams profile. It then fetches the actor document as
described in [Follows](#follows), with the same checks. The actor only counts if its `preferredUsername` and domain match
the address, ignoring case in the username, or if a WebFinger request for it to its own domain gives the address looked up as
the `subject`, as for a server whose accounts have a different domain from the server's own. A URL is fetched as an ActivityPub document, and if that's an actor
whose `id` is a different URL, such as a profile page's, the actor is fetched again from its `id`.

The follow then waits for an answer, and a `Follow` signed as the user is sent to the actor's `inbox`. Its `id` is
`{actor-uri}#follows/{uuid}`, which is new each time the user follows, so an answer to an earlier request doesn't count for
a new one. Asking to follow again while the request is waiting sends the same `Follow` again.

```json
{
  "@context": "https://www.w3.org/ns/activitystreams",
  "id": "https://elysium.example/users/alice#follows/0f8e7c3a-5b1d-4c7e-9a2f-1d3e5b7c9a0b",
  "type": "Follow",
  "actor": "https://elysium.example/users/alice",
  "object": "https://remote.example/users/bob"
}
```

An `Accept` or `Reject` is only acted on if the actor that signed it is the one being followed, and its `object` names a
`Follow` from here by its `id`, or embeds one with that `id`. An embedded `Follow` with no `id`, or with an `id` that isn't from
this server, counts for whatever follow there is between its `actor` and the signer, if its `object` is the signer or missing. An `Accept` makes the follow active, and a
`Reject` removes it, whether it was waiting or already accepted. Repeats of either are processed again. Anything else is
ignored, including an `Accept` or `Reject` of another type of activity.

When the user unfollows, or withdraws a request that's still waiting, the `Follow` isn't sent if it hasn't been already. An
`Undo` is sent either way, with the `id` `{follow-id}/undo`, and it waits until any request with the `Follow` to the same server
has ended:

```json
{
  "@context": "https://www.w3.org/ns/activitystreams",
  "id": "https://elysium.example/users/alice#follows/0f8e7c3a-5b1d-4c7e-9a2f-1d3e5b7c9a0b/undo",
  "type": "Undo",
  "actor": "https://elysium.example/users/alice",
  "object": {
    "id": "https://elysium.example/users/alice#follows/0f8e7c3a-5b1d-4c7e-9a2f-1d3e5b7c9a0b",
    "type": "Follow",
    "actor": "https://elysium.example/users/alice",
    "object": "https://remote.example/users/bob"
  }
}
```

What the followed actor posts is stored once following them is accepted, as [Receiving statuses](#receiving-statuses)
describes. Following an actor on a [blocked domain](#domain-blocks) is refused.

#### Backfill

When a `Follow` from here is accepted, the followed actor's `outbox` is fetched, signed as the
[instance actor](#instance-actor), to store what they posted before. This happens again for a later accepted follow, and once
for several follows of the same actor at the same time. Fetches share the per-host [turns](#forwarded-activities) of other
fetches made on another server's word.

- The `outbox` given in the actor document is only used if it's an HTTPS URL on the same host and port as the actor. It has to
  be an `OrderedCollection` or `Collection` whose `id` is the URL fetched. Its `first` page can be embedded, or an ID on the
  actor's host, which is fetched and has to be an `OrderedCollectionPage` or `CollectionPage` with that `id`. A collection
  with no `first` is read for its own items. Only that first page is read, and `next` is never followed.
- Up to 40 items of `orderedItems`, or `items`, are read. Only a `Create` whose `actor` is the followed actor is used, and
  `Announce`s, items that are only an ID, and other activities are skipped. The `Create`'s `object` can be an embedded
  `Note`, as Mastodon and Misskey send it, or the `Note`'s ID, as GoToSocial sends it. An ID on the actor's host and port
  that isn't stored here is fetched, signed as the instance actor, each taking its own turn, and has to come back with the
  same `id`. Either way, the `Note` has to be attributed to that actor alone, with an `id` on its host and port, and be
  public or unlisted, read [as for a `Create`](#receiving-statuses). It also needs a `published`.
- These are stored as if they'd been sent then, with the same checks on content and repeats, except that they're sorted by
  their `published` time, so they don't look newer than statuses that arrived before them. Local accounts they mention
  aren't notified.
- A `404`, `410`, or other refusal ends the backfill, and a server error or timeout is tried again later. Nothing is fetched
  if no local account still follows the actor, or the actor is suspended or on a blocked domain.

### Statuses

When a local user posts a public, unlisted, or followers-only status, it's sent to their followers on other servers, signed as
the user, and to the actors on other servers that it mentions. A direct status is only sent to the actors it mentions. Each
server's shared inbox is used for the recipients that have one, so that they share one delivery, and other recipients'
`inbox` is used otherwise. A public or unlisted reply to a status from another server is also sent to that
status's author. A mention of `@user@domain` is looked up through WebFinger when the status is posted, as
[Following other actors](#following-other-actors) describes, and one that can't be found stays as text, and isn't sent anything. Followers are
worked out shortly after posting, and statuses from before someone followed aren't sent. A
followers-only status only goes to those who were already following when it was posted.

```json
{
  "@context": ["https://www.w3.org/ns/activitystreams", {"sensitive": "as:sensitive", "Hashtag": "as:Hashtag",
    "toot": "http://joinmastodon.org/ns#", "blurhash": "toot:blurhash",
    "focalPoint": {"@container": "@list", "@id": "toot:focalPoint"}}],
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

When a local user edits a status, an `Update` of it is sent, signed as the user, to everyone its `Delete` would be sent
to, as described below. That includes the actors it mentioned before the edit, who can still fetch it, and those it now
mentions, who haven't been sent its `Create`. Its `id` is `{note-uri}#updates/{milliseconds}`, it's addressed like the
`Note`, and its `object` is the `Note` as it is when the `Update` is sent, with `updated` set. Each edit's `updated` is at
least a second later than the version before, and an edit that changes nothing sends nothing. A user can change the
text, content warning, sensitivity, and language, but not the visibility or `inReplyTo`, as on Mastodon.

```json
{
  "@context": ["https://www.w3.org/ns/activitystreams", {"sensitive": "as:sensitive", "Hashtag": "as:Hashtag",
    "toot": "http://joinmastodon.org/ns#", "blurhash": "toot:blurhash",
    "focalPoint": {"@container": "@list", "@id": "toot:focalPoint"}}],
  "id": "https://elysium.example/users/alice/statuses/019a0000-0000-7000-8000-000000000000#updates/1767229260000",
  "type": "Update",
  "actor": "https://elysium.example/users/alice",
  "to": ["https://www.w3.org/ns/activitystreams#Public"],
  "cc": ["https://elysium.example/users/alice/followers"],
  "object": {
    "id": "https://elysium.example/users/alice/statuses/019a0000-0000-7000-8000-000000000000",
    "type": "Note",
    "attributedTo": "https://elysium.example/users/alice",
    "url": "https://elysium.example/users/alice/statuses/019a0000-0000-7000-8000-000000000000",
    "published": "2026-01-01T00:00:00Z",
    "updated": "2026-01-01T01:01:00Z",
    "to": ["https://www.w3.org/ns/activitystreams#Public"],
    "cc": ["https://elysium.example/users/alice/followers"],
    "content": "<p>Hello again</p>",
    "sensitive": false
  }
}
```

`Update`s aren't ordered among themselves, so a server can get an older one last, and the `Note`'s `updated` says which
is newer. A repeat, or an `Update` sent after a later edit, can carry a newer `Note` than its `id` suggests. A `Create`
that hasn't been sent to a server yet carries the latest edit, and an `Update` doesn't overtake a `Create` that's being
sent to the same server.

When a local user deletes a status, any delivery of its `Create` or `Update`s that hasn't started is cancelled, and a
`Delete` is sent to their followers on other servers and the actors it mentioned, the same way as a `Create`. It's addressed like the `Create`,
except that it doesn't address the author of a parent status, though it's sent to them if the `Create` was. The `Delete` of
a followers-only status only goes to followers who were already following when it was posted, so that it can't tell anyone else
that the status existed. Since it goes to who follows the author when it's deleted, a server whose followers have all left
since keeps its copy.

A `Delete` doesn't overtake a `Create` or `Update` that's already being sent: it waits until that request to the same
server has ended, which takes at most 30 seconds. This server can only control when each is sent, so a server that handles activities out of order
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

#### Receiving statuses

A `Create` whose `object` is a `Note` is stored as a status, if the `Note` is for a local user: one who follows its author,
with the author's `followers` collection addressed, or one it addresses. A public or unlisted `Note` is also stored if its
`inReplyTo` is a status stored here whose author is a local user or is followed by one, as Mastodon does. That covers a
reply that Mastodon [forwards](#forwarded-activities) to the parent author's followers, which only addresses the replier's
followers and the parent's author. The parent has to be stored first, so a reply that arrives before it is ignored. A `Note`
that isn't for anyone here is ignored, even if it's public, unless it's [boosted](#boosts) or [backfilled](#backfill). Other object types, such as `Article`, `Page`, and `Question`, are ignored.

- **Who it's from:** the `Note`'s `attributedTo` has to be exactly the `Create`'s `actor`, and its `id` an HTTPS URL on the
  same host and port. Anything else is dropped.
- **Sent as an ID:** an `object` that's only an ID is fetched from there, signed as the [instance actor](#instance-actor),
  and has to come back with the same `id`. A server error or timeout is tried again later, and other failures drop it.
- **Visibility:** read from the `Note`'s own `to` and `cc`, not the `Create`'s. The public collection in `to` makes it public,
  and in `cc` unlisted. Otherwise, the author's `followers` collection in either makes it followers-only. Anything else is
  direct.
- **Mentions:** `Mention` tags are read, up to 100 of them. A followers-only or direct `Note` is only shown to the local
  users that it or its `Create` addresses one by one in `to`, `cc`, `bto`, `bcc`, or `audience`, besides followers for a followers-only
  one. A `Mention` tag alone doesn't show it to anyone. A local user it addresses without a `Mention` tag can see it, but
  isn't listed as mentioned, as on Mastodon. A public or unlisted `Note` lists every local user it tags. Actors on other
  servers are only listed as mentioned if they're already known here, and they're never fetched for this.
- **Hashtags:** a `Hashtag` tag's `name`, with or without its `#`, is the tag, up to 100 of them. Its `href` isn't
  read. Names are matched without regard to case or Unicode composition, and one that needs no letter, has characters
  other than letters, marks, digits, `_`, and an inner `·` or zero-width non-joiner, or is longer than 100 characters,
  is ignored. Hashtag links in the `content` don't count, as on Mastodon.
- **Content:** `content` is sanitized. `p`, `br`, `span`, `a`, `del`, `s`, `pre`, `code`, `em`, `strong`, `b`, `i`, `u`,
  `ul`, `ol`, `li`, and `blockquote` are kept, headings become bold paragraphs, and other elements are replaced by their
  text. Links keep only HTTP and HTTPS `href`s, and get `rel="nofollow noopener noreferrer"`. Only Mastodon's `h-card`,
  `mention`, `hashtag`, `u-url`, `invisible`, and `ellipsis` classes are kept, and no other attributes. `summary` is
  reduced to plain text and shown as a content warning, which also marks the status as sensitive.
- **Other properties:** `url` is kept if it's an HTTP or HTTPS URL, or a `Link` to one that's `text/html` or has no
  `mediaType`. `published` is kept, and the time it arrived is used if it's missing. The first language in `contentMap`
  other than `und` is used as its language. `inReplyTo` links it to its parent once the parent is stored here, even if the
  parent arrives later. Tags other than `Mention` and `Hashtag`, emoji, and polls aren't read yet.
- **Attachments:** each `Document`, `Image`, `Video`, or `Audio` in `attachment`, one object or an array, up to 16 in
  order, as Misskey allows. Its `url` is a string, a `Link`, or an array of them, and the first HTTP or HTTPS one is
  taken. One without such a URL is left out. Its `mediaType` decides whether it's shown as an image, video, or audio,
  and its type does only when there's no `mediaType`. Anything else is shown as a link. `name` is the description, as
  plain text, up to 1500 characters. `width`, `height`, `blurhash`, and a `focalPoint` of two numbers are kept when
  they're valid. Each is then downloaded once, shortly afterward, with a `GET` from the same `User-Agent` as other
  requests and no signature, over HTTPS only, following up to 3 redirects, within 2 minutes. The file's own bytes decide
  what it is, whatever its `mediaType` says. One that's too large (16 MB for an image, 100 MB for video, and 40 MB for
  audio by default), isn't an accepted format, or gets any other status than `200`, `5xx`, `408`, or `429` is linked to
  instead and not fetched again. A `5xx`, `408`, or `429` is tried again later. Only one file is downloaded from a host
  at a time. Each server's attachments can add up to only so much in an hour, 2 GB by
  default, counted by the author's server rather than the file's host, and past that they're linked. Copies are kept for
  30 days by default.
- **Repeats:** a `Note` whose `id` is already stored is ignored, so it can't be changed by sending it again. An
  [`Update`](#receiving-updates) is how it's changed.
- **Edits:** a `Note` that has an `updated` later than its `published`, and no more than an hour ahead of this server's
  clock, is stored as edited at that time, so an `Update` that isn't newer can't replace it.

What happens to the statuses of [suspended](#suspended-and-deleted-accounts) actors and [blocked](#domain-blocks) servers is
described with those.

#### Receiving updates

An `Update`'s `object` can be an ID, an embedded object, or an array of one of those.

- **Actors:** an `Update` whose `object` has the `actor`'s own `id` makes this server fetch the actor's document again,
  signed as the [instance actor](#instance-actor), however recently it was fetched. The embedded object isn't read,
  since not every server says when an actor changed, so the document's server is the only one that knows what's current.
  What's stored is [what a `Follow` stores](#follows): the username, inboxes, `followers` collection, and key. A key
  that the new document has dropped, or has with a different value, isn't used for signatures from then on, and is
  fetched again when it's next needed, as is a key on the actor's server that it now lists and that couldn't be found
  before. This holds even if the rest of the new document can't be used. A server error or timeout is tried again later.
  Other failures, including a `404` or `410`, keep the older copy, since only a [`Delete`](#receiving-deletes) removes
  an actor. An `Update` of an actor that isn't stored, or of another actor, is ignored.
- **Statuses:** an `Update` whose `object` is a `Note` is read with [the same checks as a `Create`](#receiving-statuses):
  it has to be attributed to the `actor` alone, with an `id` on the same host and port, and one sent as an ID is fetched.
  It's applied to a status that's stored here, by the same author. `content`, `summary`, `sensitive`, the language, the
  `Hashtag` tags, and the attachments are replaced, and read as they are for a `Create`. An attachment with the same
  `url` as before is updated rather than replaced. Its visibility, `inReplyTo`, `url`, and `published` aren't
  changed. Other object types, such as `Question`, are ignored.
- **Order:** the `Note` needs an `updated`, and is only applied if that's later than its `published` and the edit stored
  last. Activities are processed in no order, so one that's older is ignored, and one without `updated` is ignored too, as
  is one more than an hour ahead of this server's clock, which would keep later edits from being applied.
- **Before its `Create`:** an `Update` of a `Note` that isn't stored is treated as its `Create`, with the same checks,
  including who it's for, if its `published` is less than 3 days before the `Update` arrived. The `Create` is then ignored
  when it's processed. A `Note` that's older, or has no `published`, is ignored, so that editing an old post doesn't make
  it look new here, since statuses are ordered by when they arrive.
- **Forwarded:** an `Update` is [fetched from its origin](#forwarded-activities) like any forwarded activity. Mastodon's
  `Update` IDs have a fragment, so a forwarded one is lost.

#### Receiving deletes

A `Delete`'s `object` can be an ID, or an embedded object with an `id`, such as a `Tombstone`. It has to be an HTTPS URL on
the same host and port as the `actor`, and anything else is dropped. Its `type` isn't read.

- **Statuses:** a stored status is removed if its author is the `Delete`'s `actor`. A `Delete` of a status by someone else
  is ignored. Activities are processed in no order, so the `id` is remembered for 34 days, and a `Create` of it from the same
  actor that's processed later is ignored.
- **Actors:** a `Delete` whose `object` is its own `actor` deletes that actor, if it's stored, and removes its statuses and
  its follows in both directions and likes. Nothing is sent back. This is for good: the actor's URI is refused from then on, with a
  `403` for deliveries signed with its key, even if its server later gives the URI to a new actor. A `Delete` of an actor
  that isn't stored is ignored.
- **Checked with the object's server:** when a `Delete` can't be shown to come from its actor, its `object` is fetched,
  signed as the [instance actor](#instance-actor), and it's only acted on if that serves a `Tombstone` with the same `id`,
  or answers `404` or `410` for an actor, or for a public or unlisted status that's stored here. Servers give the same answer
  for a followers-only status they won't show the instance actor, so a `404` or `410` doesn't remove one, or keep a status
  that isn't stored yet from being stored. A server error or timeout is tried again later. This is done for a forwarded
  `Delete` that its origin doesn't serve, and for a `Delete` of an actor by itself whose signature isn't accepted: because
  its key now answers `404` or `410`, as Mastodon's do, or because the actor is [suspended](#suspended-and-deleted-accounts)
  here. Such a `Delete` is answered with a `202`, but only if the actor is stored here and not already deleted. Otherwise it
  gets the `401` or `403` it would have.

### Likes

When a local user likes a status from another server, a `Like` signed as the user is sent to the author's `inbox`, and
addressed only to them. Its `id` is `{actor-uri}#likes/{uuid}`, which is new each time. Liking a local status sends
nothing.

```json
{
  "@context": "https://www.w3.org/ns/activitystreams",
  "id": "https://elysium.example/users/alice#likes/5d6e7f80-1a2b-4c3d-8e9f-0a1b2c3d4e5f",
  "type": "Like",
  "actor": "https://elysium.example/users/alice",
  "object": "https://remote.example/users/bob/statuses/1",
  "to": ["https://remote.example/users/bob"]
}
```

Unliking cancels the `Like` if it hasn't been sent, and sends an `Undo` either way, with the `id` `{like-id}/undo` and the
`Like` embedded. It waits until any request with the `Like` to the same server has ended.

A `Like` is stored if its `object` is a local status that its actor can see: a public or unlisted one, a followers-only one
if they follow the author or are mentioned, or a direct one that addresses them. A `Like` of anything else is ignored, and
nothing is fetched for it. A repeated `Like` of the same status counts once.

An `Undo` removes a like if its `object` names the `Like` by `id`, embeds it, or embeds a `Like` with no `id` whose `object`
is the status. Only the actor that signed the `Like` can undo it. As with [follows](#follows), activities are ordered by
when they arrived here: an `Undo` covers that actor's likes of the status that arrived before it, but not a newer one, and
stops a `Like` it names from counting if that's processed later, for about 34 days.

A `Note`'s `likes` collection is an `OrderedCollection` with a `totalItems` and a `first` page, paged like
[followers and following](#actors), with up to 40 `Like` activities a page, newest first. Each has the `id` it was received
with. It's served with the same media types as the `Note`, and any origin can read it through CORS, but a request that
accepts HTML is redirected to the status's page, and any signature is ignored. Only public and unlisted statuses have one, and others get a 404, or a
403 if the author is suspended. Likes from suspended actors aren't counted or listed, and come back if the suspension ends.

### Boosts

When a local user boosts a public or unlisted status they can see, or one of their own followers-only statuses, an `Announce`
signed as the user goes to their followers and to the status's author. Its `object` is only the status's ID:

```json
{
  "@context": "https://www.w3.org/ns/activitystreams",
  "id": "https://elysium.example/users/alice/statuses/019a0000-0000-7000-8000-000000000001/activity",
  "type": "Announce",
  "actor": "https://elysium.example/users/alice",
  "published": "2026-01-02T00:00:00Z",
  "to": ["https://www.w3.org/ns/activitystreams#Public"],
  "cc": ["https://elysium.example/users/alice/followers", "https://example.com/users/bob"],
  "object": "https://example.com/users/bob/statuses/1"
}
```

It's addressed like a status of the same visibility, with the author in `cc`. A boost can be public, unlisted, or
followers-only, and a boost of a followers-only status is always followers-only. Its `id` can't be fetched. Unboosting
cancels the `Announce` if it hasn't been sent, and sends an `Undo` that embeds it, after any request with the `Announce` to
the same server has ended. So does deleting the boosted status, here or on its server. The `Undo` goes to the status's author
and to whoever follows the booster at the time, or for a followers-only boost, only to those who were already following when
it was made. A local status's `Delete` also
goes to the followers of local users who boosted it.

An `Announce` is only read for its `object`'s ID, and an embedded copy is never used. A boost of a local public or unlisted
status is always kept. A boost of another server's status is only kept if it's addressed to the booster's `followers`
collection and a local user follows the booster. Naming a local user isn't enough, as on Mastodon. If that status isn't stored here, it's fetched from its `id`, signed as the
[instance actor](#instance-actor), once its host has a [turn](#forwarded-activities). It has to come back as a `Note` with
exactly that `id`, attributed to one actor on the same host and port, and addressed to the public in `to` or `cc`. Anything
else drops the boost, and a server error or timeout is tried again later. The `Note` is then stored as that actor's status.
A followers-only or direct status can only be boosted by its author.

The `Announce`'s visibility comes from its own `to` and `cc`, read as for a `Note`, and a direct one is ignored. A boost keeps
the `Announce`'s `id`, and a later `Announce` of the same status from the same actor takes it over, so an `Undo` of the older
one leaves the boost in place. An `Undo` or a `Delete` of a boost has to name the `Announce`'s `id`, embedded or alone, and
only the actor that sent it can withdraw it. One that arrives before its `Announce` stops it from being stored, or what it
boosts from being fetched, for about 34 days.

A `Note`'s `shares` collection is served like its `likes`, and counts and lists only public and unlisted boosts, as embedded
`Announce`s whose only addressing is the public collection, in `to` for a public boost and in `cc` for an unlisted one.
Boosts from suspended actors, and of a suspended author's statuses, aren't counted or listed, and come back if the suspension
ends.

### Differences from Mastodon

- An actor's `manuallyApprovesFollowers` isn't read, so local users see every account on another server as unlocked.
- Only `Note`s are stored. A `Like` of a status from another server is ignored. A reply's parent isn't fetched, and neither
  are the replies to a status.
- Profiles of actors on other servers aren't stored, so an `Update` of one only refreshes its inboxes, collections, and key.
- An `Update` from another server doesn't change who its status mentions, so an edit can't show a followers-only or
  direct status to more local users.
- A reply's `inReplyTo` is left out if its parent can't be shown, so it doesn't look like a reply.
- A followers-only status can't be fetched by an actor that started following after it was posted, though Mastodon would
  serve it to any current follower.
- A local user's boost of a status that's removed with its author's account, or with a blocked domain's statuses, goes
  without an `Undo`.
- Fetches don't need a signature. Only fetches of `Note`s check one, and any signature on other fetches is ignored.
- Backfill only reads the first page of an outbox, and only its `Create`s.

### Suspended and deleted accounts

Admins can [suspend or delete an account](docs/accounts.md#suspending-and-deleting-accounts). Other servers get these answers
for a local account:

| Request                 | Suspended                                       | Deleted                                         |
|-------------------------|-------------------------------------------------|-------------------------------------------------|
| Actor document          | `200`, with `suspended: true` and the same key  | `410`, with a `Tombstone` (`formerType: Person`) |
| `Note`                  | `403`                                           | `410`, with a `Tombstone` (`formerType: Note`)  |
| Followers and following | `403`                                           | `410`                                           |
| WebFinger               | `200`                                           | `410`                                           |
| Personal inbox          | As usual                                        | `410`                                           |
| Profile and status page | `404`                                           | `404`                                           |

`suspended` is `toot:suspended` (`http://joinmastodon.org/ns#suspended`), and the `@context` only defines it on a document
that has it. A suspension isn't a `404` or `410`, so that other servers don't take it as the actor or its statuses being gone
for good. A deleted account's `Note`s all get the same answer, whatever their ID, so this doesn't say which ones existed.

A suspended account's actor document has no profile: no `name`, `summary`, `icon`, `image`, `attachment`, or `updated`.

- **Suspending a local account:** Its followers' servers get an `Update` of the actor with `suspended: true`, and an `Update`
  without it if the suspension ends, each signed by the actor, with its own ID. They aren't ordered, so if a server is down
  through a quick suspension and its end, the older `Update` can arrive last, and that server has it wrong until it next
  refreshes the actor. Followers are kept. A status's `Create` or `Update` still queued is dropped, and isn't sent if the suspension ends. Other
  activities still queued, such as a status's `Delete` or a follow's `Accept`, wait and are sent within 10 minutes of the
  suspension ending, unless that takes more than 33 days. A `Follow` of a suspended account gets no answer, and an `Undo`
  of one works as usual.
- **Deleting a local account:** Its followers' servers get a `Delete` signed by the actor, whose `object` is the actor's URI, with
  the ID `{actor-uri}#delete`. No `Delete` is sent for each status. The actor document answers `410` straight away, so a server has
  to verify the `Delete` with a key it already has. Deliveries can be retried for up to 34 days.
  Who's told comes from who follows the account then, so a server whose followers have all left keeps its copy. Each account
  on another server that it follows, or has asked to follow, also gets an `Undo` of the `Follow`, the same as for
  [unfollowing](#following-other-actors).
- **Suspending a remote actor:** A delivery signed with its key gets a `403`, and its activities are refused, including any
  still queued, apart from its own `Delete` of itself, which is [checked with its server](#receiving-deletes). Its document isn't fetched or refreshed. Its follows are removed, and aren't restored if the suspension ends, so
  it would have to follow again. Nothing more is sent to its personal inbox, but a shared inbox still gets activities for
  other followers. Its stored statuses stop being shown, and come back if the suspension ends. Its own server isn't told. Only
  an actor that's already stored can be suspended.
- **Deleting a remote actor:** this happens when its server [sends a `Delete`](#receiving-deletes) of it, and is treated
  like a suspension that can't end, except that its statuses are removed too.

### Domain blocks

Admins can [block a domain](docs/accounts.md#domain-blocks), which also covers its subdomains and every port. Domains are
compared in lowercase ASCII, without a trailing dot. A block is only ever a full one, with no lesser level.

- **Inbound:** A delivery signed with a key on a blocked domain gets a `403`, before any key is fetched, so it doesn't use up
  any limits. This also applies to keys this server has already cached. A signature that's malformed or out of date still
  gets its usual `400` or `401`, because those are checked first. Activities that were already queued are dropped without
  being processed.
- **Fetching:** Keys, actors, and boosted statuses are never fetched from a blocked domain, and a boost by one of its
  actors isn't stored.
- **Delivery:** Nothing is sent to an inbox on a blocked domain. Deliveries that were already queued finish without being
  sent, and that doesn't count as the server failing.
- **Existing data:** Follows to and from its actors are removed, and so are their likes, boosts, and statuses, and boosts of
  their statuses. Its actors are kept.
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
[RFC 9421](https://www.rfc-editor.org/rfc/rfc9421) isn't implemented. Inboxes require signatures, and fetches of `Note`s
check one if it's sent. The only requests this server makes are fetches of keys, actors, outboxes, and other servers' activities
and `Note`s, unsigned WebFinger lookups, and deliveries of `Follow`, `Undo`, `Accept`, `Create`, `Update`, `Delete`, `Like`, and `Announce`.

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
aren't reported. `openRegistrations` is `true` when anyone can ask for an account, even if an admin has to approve it, as
on Mastodon.

## Additional documentation

- [README](README.md)
