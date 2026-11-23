# Federation test lab

The lab runs real fediverse servers alongside Elysium to test federation. They communicate over HTTPS through a
reverse proxy on a Docker network with no route to public servers. All lab credentials are public and are for this lab
only.

## Running it

You need Docker, `jq`, and `openssl`.

```shell
./gradlew bootBuildImage
federation/smoke.sh gotosocial   # or mastodon, or misskey
```

**Warning:** The script permanently deletes any existing lab before starting a new one. It exits with an error if a
check fails. Rebuild the Elysium image before rerunning the lab with local code changes.

The script checks:

- **Discovery.** The peer resolves `@alice@elysium.test` through the proxy. With authorized fetch on, an unsigned
  request for Alice’s actor returns the shorter document. The peer signs its other fetches.
- **Refusing deliveries and fetches.** An unsigned inbox `POST` and an unsigned fetch of Alice’s followers both return
  `401`.
- **Accepting deliveries.** The peer posts a status that mentions Alice. The script checks `inbound_activity` to
  confirm Elysium fetched the sender’s key and verified the signature. With `AUTHORIZED_FETCH` on, Mastodon must
  accept Elysium’s signed fetch and its instance actor.
- **Following.** The peer’s `lab` user follows Alice, receives the signed `Accept`, and counts the follow. Elysium’s
  `follow` row appears, disappears on unfollow, and returns on a new follow.

  Misskey runs two follow and unfollow cycles, then follows again. It uses a new ID for each `Follow` and reuses the
  same `Undo` ID for the pair of actors. The script expects three `Follow` rows and one `Undo` row in
  `inbound_activity` to verify that it is exercising ID reuse.
- **Locked accounts.** Alice turns on manual follow approval while `lab` follows her, and the peer shows her as locked
  after receiving the actor `Update`. When `lab` unfollows and requests to follow again, Elysium lists the request and
  sends no `Accept`, and the peer shows the request as pending.

  Alice rejects the request, and the peer drops it after receiving the `Reject`. Alice approves a new request, and the
  peer shows `lab` following her. She turns off manual approval for later checks.
- **Blocks.** Alice blocks `lab` while it follows her. Elysium removes the follow and sends a `Block`, and the peer
  drops the follow and shows the block. Alice unblocks `lab`, and the peer clears the block after receiving the
  `Undo`.

  Then `lab` follows Alice again.
- **Following out.** Alice uses the client API to resolve `lab` through WebFinger and follow it. The peer accepts the
  signed `Follow` and Elysium shows `following`. On GoToSocial, it first stays pending until `lab` approves it.

  Alice’s `following` collection then lists `lab`.

  When `lab` changes its display name, Elysium receives the `Update` and refetches the actor. After the follow is
  accepted, a post that predates the follow and was never delivered to Elysium appears among `lab`’s statuses. That
  confirms Elysium read its outbox. When Alice unfollows, the peer accepts the `Undo`.
- **Receiving edits:** on Mastodon and GoToSocial, `lab` edits its status that mentions alice, and Elysium has to store the
  new text, marked as edited. Misskey can't edit notes.
- **Receiving deletes.** Elysium removes its copy when `lab` deletes the status that mentioned Alice. The peer then
  creates a separate `gone` account, since later checks need `lab` and Misskey cannot delete its admin.

  The new account follows Alice, or Alice follows it on GoToSocial, which sends account deletions only to followers.
  When the peer deletes `gone`, Elysium marks the actor as deleted and removes its follows.
- **Sending statuses.** Alice posts a public and a followers-only status. The peer accepts both signed `Create`
  activities and stores each with the correct visibility. An unsigned fetch of the followers-only status returns
  `401`.

  GoToSocial and Misskey show both posts in the home timeline, and the Misskey check also verifies their text. The
  script waits until both posts are stored because GoToSocial 0.22.3 stops updating a timeline until restart if its
  first load contains exactly one post. It marks Mastodon’s `lab` user as recently signed in
  so Mastodon fills that user’s home feed.
- **Likes.** The peer receives Alice’s favourite and unfavourite of the remote post. After the peer likes and unlikes
  Alice’s public and followers-only posts, Elysium updates the counts and sends favourite notifications. The check
  also verifies `Like` activities in the public post’s collection.
- **Boosts:** alice boosts and unboosts the remote lab post, and the peer's count has to follow. The peer then boosts and
  unboosts alice's public post. Elysium must update `reblogs_count`, emit a reblog notification, and list the `Announce` in
  the post's shares collection.
- **Pins.** Pinning and unpinning Alice’s public post updates Elysium’s `featured` collection. Mastodon and Misskey
  also show the change after receiving the `Add` and `Remove`. GoToSocial reads `featured` only when it refreshes an
  actor, so the script skips that check there.

  The peer then pins and unpins the post that mentions Alice, and Elysium’s `?pinned=true` results change. Since
  GoToSocial sends no `Add`, the script changes the profile after each action so Elysium gets an `Update` and reads
  `featured` again.
- **Quotes.** Alice quotes a `lab` post, and Elysium marks the quote accepted when the peer approves it. Mastodon also
  shows approval. On Mastodon, the script checks that editing the quote to use another post gets approval and that
  removing the quote clears it.

  GoToSocial allows only authors to quote their own posts, so the check there expects Elysium to refuse Alice’s quote.

  The peer then quotes Alice’s public post. Elysium approves the quote automatically and notifies Alice. On Mastodon,
  Alice revokes the quote and the peer shows it as revoked.
- **Reviewing quotes.** On Mastodon and Misskey, the peer quotes a post twice after Alice sets it to require review.
  Both quotes appear in Alice’s interaction requests, and they stay pending on Mastodon while Elysium waits to answer
  the `QuoteRequest`. Alice approves one and rejects the other, and Mastodon shows both decisions.

  Alice later approves the rejected quote. Elysium shows that approval, but Mastodon only accepts an `Accept` for a
  pending quote. Misskey sends no approval requests, so Elysium sends no decisions back.
- **Boosts of other posts:** while alice follows the `lab` user, it boosts a post by another account on its server, `other`,
  which Elysium hasn't been sent. Elysium has to fetch that post and show the boost in alice's home timeline, and remove the
  boost when it's undone.
- **Polls:** alice posts a poll, and the other server's `lab` user votes in it once it's stored there. Elysium has to count
  the vote and queue the `Update` with the new counts. `lab` then posts a poll, which Elysium has to store as one, and alice
  votes in it. The other server has to count her vote.
- **Reports.** The peer reports Alice and includes her public post where supported. On Misskey, an admin forwards the
  report. Elysium stores the forwarded report and post and notifies Alice as an admin.

  Alice then reports `lab` and forwards the report with the post mentioning her. The peer verifies the `Flag` signed
  by Elysium’s instance actor and stores the report with its comment. Mastodon records Elysium’s instance actor as the
  reporter, and Mastodon and GoToSocial record the post. On GoToSocial, `lab` is an admin so the script can read the
  report.
- **Deleting statuses:** alice deletes both statuses through the client API. The other server has to accept the signed `Delete`
  for each and remove its copy. Fetching the public status has to get a `410` with a `Tombstone`, and the followers-only one
  a `404`.
- **Sending media:** alice uploads `federation/fixtures/image.png` and posts it in a sensitive status. Its `Note` has to
  have a `Document` for it, and the other server has to store the status with one attachment, whose remote URL is on
  Elysium on Mastodon and GoToSocial.
- **Receiving media:** on Mastodon, `lab` posts the same image in a status mentioning alice, and Elysium has to show the status with one
  `image` attachment, its description, and a `remote_url` on Mastodon, once it's downloaded and served from Elysium.
- **Changing a profile:** alice changes her display name through the client API, while the other server's `lab` user
  still follows her. The other server has to accept the signed `Update` of her actor, and show the new name.
- **Suspending and deleting accounts:** Another admin suspends alice, ends the suspension, and deletes alice's account through the
  admin API. The other server has to accept the signed `Update` and `Delete` for these, including the `Delete` while alice's
  actor document answers `410`. Mastodon also has to mark the account as suspended, then not suspended, and then remove it.

## Looking into a failure

The lab stays up after the script exits. Use these commands to inspect logs, fetch an actor, and check the database.

```shell
export COMPOSE_FILE=compose.federation.yaml COMPOSE_PROFILES=gotosocial   # or mastodon, or misskey
docker compose logs elysium gotosocial
docker compose run --rm -T driver curl -sS -H 'Accept: application/activity+json' https://elysium.test/users/alice
docker compose exec -T postgres psql -U elysium -d elysium -c 'SELECT activity_id, actor_uri, type FROM inbound_activity'
docker compose exec -T postgres psql -U elysium -d elysium -c 'SELECT state, activity_uri FROM follow'
docker compose exec -T postgres psql -U elysium -d elysium -c 'SELECT activity_id, body FROM outbound_activity'
docker compose exec -T postgres psql -U elysium -d elysium -c \
    'SELECT task_name, consecutive_failures, execution_time FROM scheduled_tasks WHERE consecutive_failures > 0'
```

**Warning:** This command permanently deletes the lab and its volumes.

```shell
docker compose --profile '*' down -v
```

The lab publishes no ports. Its domains resolve only on the lab network, so run requests from the `driver` service.

For details about refused deliveries, enable debug logging in the `elysium` service’s `environment` in
`compose.federation.yaml`.

```yaml
LOGGING_LEVEL_DEV_BIRIBIRI_ELYSIUM_ACTIVITYPUB: debug
```

## How the lab differs from production

Two settings let Elysium reach peers inside the lab. The rest of the federation path works as in production.

- **Reaching private addresses:** `elysium.federation.allowed-private-networks` lets `FederationHttpClient` reach
  `172.30.0.0/24`. Don't add an on/off switch or allow plain HTTP.
- **Trusting the lab's CA:** `generate-certs.sh` writes the CA to a service binding, which the image's buildpack adds to the
  JVM's trust store at startup.

## In CI

`.github/workflows/federation.yml` runs:

| Job                                  | When                                                                          |
|--------------------------------------|-------------------------------------------------------------------------------|
| GoToSocial, at the version pinned in `compose.federation.yaml` | Pull requests and pushes to `main` that touch the server or the lab, and nightly |
| Mastodon and Misskey, at their pinned versions | Nightly, on request, and on pull requests with the `federation` label |
| All three, at their latest versions  | Nightly and on request                                                        |

On failure, the workflow uploads logs from every container. If only the latest-version jobs fail, check for changes in
peer software.
