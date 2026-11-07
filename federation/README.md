# Federation test lab

Runs real fediverse servers next to Elysium, and checks that they can talk to each other. Everything happens over HTTPS,
through a reverse proxy, on a Docker network with no route out, so nothing here can reach real servers. All of its
passwords and secrets are public, so don't use any of this for a real server.

## Running it

You need Docker and `jq`.

```shell
./gradlew bootBuildImage
federation/smoke.sh gotosocial   # or mastodon, or misskey
```

The script deletes any lab left over from an earlier run, starts a new one, and exits with an error if a check fails.
Rebuild the image to test a change to Elysium, because the lab doesn't build it for you.

The script checks eleven things:

- **Discovery:** Elysium serves its documents through the proxy, and the other server can resolve `@alice@elysium.test`.
- **Refusing deliveries:** An unsigned `POST` to Elysium's inbox gets a `401`.
- **Accepting deliveries:** The other server posts a status mentioning alice, and it ends up in Elysium's
  `inbound_activity` table. This means that Elysium fetched the sender's key and verified the signature. Mastodon runs
  with `AUTHORIZED_FETCH` on, so it has to accept Elysium's signed fetch and its instance actor as well.
- **Following:** The other server's `lab` user follows alice. The other server has to accept Elysium's signed `Accept`, which
  it shows by counting the follow, and Elysium's `follow` table has to have the row. After an unfollow, the row has to go,
  and following again has to work. Misskey goes through this twice and then follows once more, since it uses the same ID for
  each `Undo` between two actors, and a new one for each `Follow`. The script checks that `inbound_activity` has 3 `Follow`
  rows and 1 `Undo` row, so it fails if Misskey stops reusing the ID and the check stops testing that.
- **Following out:** alice looks up `lab` on the other server through Elysium's client API, which makes Elysium use
  WebFinger, and follows it. The other server has to accept the signed `Follow`, which Elysium shows as `following`. On
  GoToSocial, whose users approve followers by hand by default, the request waits until `lab` approves it, which tests the
  waiting state too. alice's `following` collection then has to list `lab`. While alice follows it, `lab` changes its
  display name, and Elysium has to take the `Update` and fetch `lab`'s actor again. alice then unfollows, and the other
  server has to take the `Undo`.
- **Receiving edits:** on Mastodon and GoToSocial, `lab` edits its status that mentions alice, and Elysium has to store the
  new text, marked as edited. Misskey can't edit notes.
- **Receiving deletes:** `lab` deletes its status that mentions alice, and Elysium has to remove its copy. The other server
  then creates a `gone` account, which follows alice, or on GoToSocial is followed by her, since GoToSocial only tells a
  deleted account's followers. Once it's deleted, Elysium has to mark the actor as deleted and remove its follows. This uses a
  second account because later checks need `lab`, and Misskey can't delete `lab`, which is its admin.
- **Sending statuses:** alice posts a public and a followers-only status. The other server has to accept the signed `Create`
  for each and store it with the right visibility, though it can't fetch the followers-only one, which gets a `404`.
  GoToSocial also has to show both in the `lab` user's home timeline. That's only read once they're stored, because in
  GoToSocial 0.22.3, a timeline that first loads with exactly one status stops updating until a restart. Misskey has to show
  both in the `lab` user's home timeline too, with the text that was posted. Mastodon has to have both in the `lab` user's home
  feed, which it only fills for users who signed in lately, so the script marks that user as signed in.
- **Deleting statuses:** alice deletes both statuses through the client API. The other server has to accept the signed `Delete`
  for each and remove its copy. Fetching the public status has to get a `410` with a `Tombstone`, and the followers-only one
  a `404`.
- **Changing a profile:** alice changes her display name through the client API, while the other server's `lab` user
  still follows her. The other server has to accept the signed `Update` of her actor, and show the new name.
- **Suspending and deleting accounts:** Another admin suspends alice, ends the suspension, and deletes alice's account through the
  admin API. The other server has to accept the signed `Update` and `Delete` for these, including the `Delete` while alice's
  actor document answers `410`. Mastodon also has to mark the account as suspended, then not suspended, and then remove it.

## Looking into a failure

The lab keeps running after the script finishes:

```shell
export COMPOSE_FILE=compose.federation.yaml COMPOSE_PROFILES=gotosocial   # or mastodon, or misskey
docker compose logs elysium gotosocial
docker compose run --rm -T driver curl -sS -H 'Accept: application/activity+json' https://elysium.test/users/alice
docker compose exec -T postgres psql -U elysium -d elysium -c 'SELECT activity_id, actor_uri, type FROM inbound_activity'
docker compose exec -T postgres psql -U elysium -d elysium -c 'SELECT state, activity_uri FROM follow'
docker compose exec -T postgres psql -U elysium -d elysium -c 'SELECT activity_id, body FROM outbound_activity'
docker compose exec -T postgres psql -U elysium -d elysium -c \
    'SELECT task_name, consecutive_failures, execution_time FROM scheduled_tasks WHERE consecutive_failures > 0'
docker compose --profile '*' down -v
```

The domains only exist on the lab's network, so requests have to come from the `driver` service. No ports are published.

Elysium only logs why it refused a delivery at debug level. To see it, add this to the `elysium` service's `environment`
in `compose.federation.yaml`:

```yaml
LOGGING_LEVEL_DEV_BIRIBIRI_ELYSIUM_ACTIVITYPUB: debug
```

## How the lab differs from production

Two settings on the `elysium` service let it reach the other servers, and the rest of the path is the same as production:

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

If a job fails, its workflow run has every container's logs as an artifact. A failure that's only in the latest versions
usually means another server changed, not Elysium.
