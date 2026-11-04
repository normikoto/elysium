# Federation test lab

Runs real fediverse servers next to Elysium, and checks that they can talk to each other. Everything happens over HTTPS,
through a reverse proxy, on a Docker network with no route out, so nothing here can reach real servers. All of its
passwords and secrets are public, so don't use any of this for a real server.

## Running it

You need Docker and `jq`.

```shell
./gradlew bootBuildImage
federation/smoke.sh gotosocial   # or mastodon
```

The script deletes any lab left over from an earlier run, starts a new one, and exits with an error if a check fails.
Rebuild the image to test a change to Elysium, because the lab doesn't build it for you.

Today, the only check is discovery: Elysium serves its documents through the proxy, and the other server can resolve
`@alice@elysium.test`. Inboxes and activities don't exist yet, so nothing can test them.

## Looking into a failure

The lab keeps running after the script finishes:

```shell
export COMPOSE_FILE=compose.federation.yaml COMPOSE_PROFILES=gotosocial   # or mastodon
docker compose logs elysium gotosocial
docker compose run --rm -T driver curl -sS -H 'Accept: application/activity+json' https://elysium.test/users/alice
docker compose --profile '*' down -v
```

The domains only exist on the lab's network, so requests have to come from the `driver` service. No ports are published.

## In CI

`.github/workflows/federation.yml` runs:

| Job                                  | When                                                                          |
|--------------------------------------|-------------------------------------------------------------------------------|
| GoToSocial, at the version pinned in `compose.federation.yaml` | Pull requests and pushes to `main` that touch the server or the lab, and nightly |
| Mastodon, at its pinned version      | Nightly, on request, and on pull requests with the `federation` label          |
| Both, at their latest versions       | Nightly and on request                                                        |

If a job fails, its workflow run has every container's logs as an artifact. A failure that's only in the latest versions
usually means another server changed, not Elysium.

## Before testing inboxes and delivery

Elysium doesn't make any requests that the lab can trigger yet, so two things it'll need were left out until they can be
tested. Add them in the first pull request where Elysium sends requests to the other servers:

- **Reaching private addresses:** `FederationHttpClient` refuses them, and the lab's addresses are all private. Add a
  setting listing the networks to allow, empty by default, and set it to `172.30.0.0/24` here. Don't add an on/off switch
  or allow plain HTTP, so that the lab keeps testing the same path as production.
- **Trusting the lab's CA:** Mount `/certs/ca.crt` into the `elysium` service, and make the JVM trust it. GoToSocial and
  Mastodon already do through `SSL_CERT_FILE`, which Java doesn't read.
