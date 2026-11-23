# Installation

## Before going live
For a public server, run a reverse proxy such as nginx or Caddy to handle HTTPS. Configure the proxy and Elysium as
described in [Reverse proxies](configuration.md#reverse-proxies).

## Downloading
The JAR in each [release](https://github.com/normikoto/elysium/releases) requires Java 25 or later. Download the
latest JAR and verify its checksum.
```shell
curl -fLO https://github.com/normikoto/elysium/releases/latest/download/elysium.jar
curl -fLO https://github.com/normikoto/elysium/releases/latest/download/elysium.jar.sha256
sha256sum -c elysium.jar.sha256
```

The check should print `elysium.jar: OK`. Copy the verified JAR to the directory you want to install it in. Docker
installs can use the release image directly. See [Running with Docker Compose](#running-with-docker-compose).

### Building from source
To build Elysium from a checkout, install JDK 25 or later and run the following command.
```shell
./gradlew bootJar
```

The JAR is written to `build/libs/elysium.jar`. Run `./gradlew bootBuildImage` to build a container image tagged
`elysium:latest`. To use that image with Compose, set `image: 'elysium:latest'` and `pull_policy: never` in
`compose.yaml`. If the build cannot connect to Docker, as can happen with Docker Desktop or Colima, run `./gradlew
bootBuildImage -PbindDockerHostToBuilder=false`.

## Running
To start the JAR with your configuration, run these commands.
```shell
# Copy from application.properties.example, which each release has too
export SPRING_CONFIG_ADDITIONAL_LOCATION=/path/to/application.properties
export SPRING_PROFILES_ACTIVE=prod
java -jar elysium.jar
```

Elysium stores uploads in `media` under the working directory unless you set `elysium.media.directory`. Back up the
upload directory and the database together. See [Configuration](configuration.md) for the settings and [Accounts and
sign-ups](accounts.md) to create your first account. For a public server, also [set up a reverse
proxy](#before-going-live).

[Check that the server works](#checking-that-it-works) after startup.

## Running with Docker Compose
Compose runs Elysium and PostgreSQL. Release images support amd64 and arm64 and require only Docker. Run the following
in an empty directory.
```shell
curl -fLO https://github.com/normikoto/elysium/releases/latest/download/compose.yaml
curl -fL -o .env https://github.com/normikoto/elysium/releases/latest/download/env.example
# Edit .env to set your server's URL and a database password...
docker compose up -d
```

PostgreSQL is reachable only within the Docker network. Back up the `postgres-data` and `media-data` volumes together
to preserve the database and uploads.

Elysium listens on `127.0.0.1:8080`, so run the HTTPS proxy on the same host. Compose already sets
`server.forward-headers-strategy=native`. The proxy must still [set the forwarded
headers](configuration.md#reverse-proxies) to stop clients from bypassing IP-based rate limits.

To upgrade, keep your `.env` and replace `compose.yaml` with the release copy so the container receives any new
settings. Then pull and start the new image.
```shell
docker compose pull
docker compose up -d
```

[Check that the server works](#checking-that-it-works) after startup or an upgrade.

## Running as a service
On Linux with systemd, use `elysium.service` to run Elysium as a dedicated user in a sandbox and start it at boot. For
a public server, [set up a reverse proxy](#before-going-live). Run the following as root.
```shell
# Create the user and group
useradd --system --user-group --home-dir /opt/elysium --shell /usr/sbin/nologin elysium

# Download the service file and the example config, next to the JAR you downloaded
curl -fLO https://github.com/normikoto/elysium/releases/latest/download/elysium.service
curl -fLO https://github.com/normikoto/elysium/releases/latest/download/application.properties.example

# Install the JAR. The server only needs to read it, so root keeps ownership.
install -d -m 755 /opt/elysium
install -m 644 elysium.jar /opt/elysium/elysium.jar

# Install your config, which only the elysium group can read because it contains your database password
install -d -m 750 -g elysium /etc/elysium
install -m 640 -g elysium application.properties.example /etc/elysium/application.properties
# Edit /etc/elysium/application.properties...

cp elysium.service /etc/systemd/system
systemctl daemon-reload
systemctl enable --now elysium.service
```

Use `systemctl edit elysium` for service overrides so they survive upgrades.

To keep secrets out of `application.properties`, set them in `/etc/elysium/elysium.env` before starting the service.
For example, put the database password in `SPRING_DATASOURCE_PASSWORD`. systemd reads this file as root.
```shell
install -m 600 /dev/null /etc/elysium/elysium.env
# Add a line to /etc/elysium/elysium.env: SPRING_DATASOURCE_PASSWORD=...
```

The sandbox allows writes to private `/tmp` and `/var/tmp` directories, shared `/dev/shm`, and `/var/lib/elysium`,
which holds uploads. Back up `/var/lib/elysium` with the database. Install Java and the JAR outside `/home`, `/root`,
and `/run/user`, which the sandbox hides. See the comments in `elysium.service` to allow writes elsewhere or bind a
port below 1024.

[Check that the server works](#checking-that-it-works) after startup.

## Checking that it works
Run the health check on the host after startup.
```shell
curl http://127.0.0.1:8080/actuator/health
```

Expect `"status":"UP"` in the response. If the check fails, read the logs with `docker compose logs app` or
`journalctl -u elysium`, depending on your setup.

After [creating your first account](accounts.md) and setting up the reverse proxy, check whether other servers can
find the account through your public URL.
```shell
curl 'https://social.example/.well-known/webfinger?resource=acct:alice@social.example'
```

For this example, the JSON response should link to `https://social.example/users/alice`. If the link uses the wrong
address, check `elysium.instance.base-url`. If the request fails, check the [reverse
proxy](configuration.md#reverse-proxies).
