# Installation

## Before going live
A public server must run behind a reverse proxy, such as nginx or Caddy, that handles HTTPS. See
[Reverse proxies](configuration.md#reverse-proxies) for what to set up, on the proxy and on the server.

## Downloading
Each [release](https://github.com/normikoto/elysium/releases) has the server as a JAR, which needs Java 25 or later to
run. To download the newest one and check that it arrived intact:
```shell
curl -fLO https://github.com/normikoto/elysium/releases/latest/download/elysium.jar
curl -fLO https://github.com/normikoto/elysium/releases/latest/download/elysium.jar.sha256
sha256sum -c elysium.jar.sha256
```

It should print `elysium.jar: OK`. Copy `elysium.jar` into a suitable location. With Docker Compose, you don't need the
JAR: see [Running with Docker Compose](#running-with-docker-compose).

### Building from source
To run a version that isn't released, such as one with your own changes, you'll need JDK 25 or later. In a checkout of
the repository, run:
```shell
./gradlew bootJar
```

This builds `build/libs/elysium.jar`. To build a container image instead, run `./gradlew bootBuildImage`, which builds
`elysium:latest`. To run it with Docker Compose, set `image: 'elysium:latest'` and `pull_policy: never` in
`compose.yaml`, so that Docker doesn't look for it online. If the image build can't connect to Docker, which can happen
with Docker Desktop or Colima, build it with `./gradlew bootBuildImage -PbindDockerHostToBuilder=false`.

## Running
To run:
```shell
# Copy from application.properties.example, which each release has too
export SPRING_CONFIG_ADDITIONAL_LOCATION=/path/to/application.properties
export SPRING_PROFILES_ACTIVE=prod
java -jar elysium.jar
```

Uploaded images are kept in `media`, in the directory you run it from, unless you set `elysium.media.directory`. Back it
up along with the database. See [Configuration](configuration.md) for the settings you can change, and
[Accounts and sign-ups](accounts.md) to create your first account. A public server also needs a [reverse proxy](#before-going-live).

## Running with Docker Compose
`compose.yaml` runs the server and a PostgreSQL database. The server's image comes from each release, for amd64 and
arm64 machines, so you only need Docker. In an empty directory:
```shell
curl -fLO https://github.com/normikoto/elysium/releases/latest/download/compose.yaml
curl -fL -o .env https://github.com/normikoto/elysium/releases/latest/download/env.example
# Edit .env to set your server's URL and a database password...
docker compose up -d
```

PostgreSQL isn't reachable from outside its Docker network, and its data is kept in the `postgres-data` volume.
Uploaded images are kept in the `media-data` volume, so back up both together. The
server listens on `127.0.0.1:8080`, so you need a reverse proxy on the same host to handle HTTPS. Because the proxy is
the only way to reach the server, `server.forward-headers-strategy=native` is already set. The proxy still needs to
[set the forwarded headers itself](configuration.md#reverse-proxies). Otherwise clients may be able to get around the rate
limits by making up their addresses.

To upgrade to the newest release, download its `compose.yaml` over yours, since a release can pass new settings on
from `.env`, and keep your `.env`. Then run:
```shell
docker compose pull
docker compose up -d
```

## Running as a service
If you're using a Linux distribution with systemd, you can use the `elysium.service` file to start the application on
boot. It runs the server as a dedicated `elysium` user, in a sandbox. Like any public server, it needs a
[reverse proxy](#before-going-live) in front of it. As root:
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

To change a setting in the service file, use `systemctl edit elysium` instead of editing the file, so that installing a
newer version of it doesn't lose your change.

To keep a secret out of `application.properties`, such as through `SPRING_DATASOURCE_PASSWORD`, put it in
`/etc/elysium/elysium.env` before starting the service. systemd reads this file as root, so the server's user doesn't need
access:
```shell
install -m 600 /dev/null /etc/elysium/elysium.env
# Add a line to /etc/elysium/elysium.env: SPRING_DATASOURCE_PASSWORD=...
```

The sandbox makes the filesystem read-only apart from private `/tmp` and `/var/tmp` directories, `/dev/shm`, which
isn't private, and `/var/lib/elysium`, where uploaded images are kept. Back that up along with the database. It also hides `/home`, `/root` and `/run/user`, so `java` and the JAR must be installed outside them. If
you need the server to write somewhere, or to listen on a port below 1024, the comments in `elysium.service` explain what
to change.

## Checking that it works
Once the server has started, ask it whether it's ready, on the host it runs on:
```shell
curl http://127.0.0.1:8080/actuator/health
```

It should answer with `"status":"UP"`. If it doesn't answer, the log says why: see `docker compose logs app` with Docker
Compose, or `journalctl -u elysium` for the service.

Once you've [created your first account](accounts.md), such as `alice`, and set up the reverse proxy, check that other
servers can find it through your public URL:
```shell
curl 'https://social.example/.well-known/webfinger?resource=acct:alice@social.example'
```

The JSON it answers with should link to `https://social.example/users/alice`. If the link has the wrong address, check
`elysium.instance.base-url`. If nothing answers, check the [reverse proxy](configuration.md#reverse-proxies).
