# Installation

## Building
You'll need JDK 25 or later installed on your system. Run the following:
```shell
./gradlew bootJar
```

Copy the resulting `build/libs/elysium.jar` into a suitable location.

## Running
To run:
```shell
# Copy from application.properties.example in this repo
export SPRING_CONFIG_ADDITIONAL_LOCATION=/path/to/application.properties
export SPRING_PROFILES_ACTIVE=prod
java -jar elysium.jar
```

See [Configuration](configuration.md) for the settings you can change, and [Accounts and sign-ups](accounts.md) to create
your first account.

## Running with Docker Compose
`compose.yaml` runs the server and a PostgreSQL database. You'll need Docker, as well as JDK 25 or later to build the
image:
```shell
./gradlew bootBuildImage
cp .env.example .env
# Edit .env to set your server's URL and a database password...
docker compose up -d
```

If the image build can't connect to Docker, which can happen with Docker Desktop or Colima, build it with
`./gradlew bootBuildImage -PbindDockerHostToBuilder=false`.

PostgreSQL isn't reachable from outside its Docker network, and its data is kept in the `postgres-data` volume. The
server listens on `127.0.0.1:8080`, so you need a reverse proxy on the same host to handle HTTPS. Because the proxy is
the only way to reach the server, `server.forward-headers-strategy=native` is already set. The proxy still needs to
[send the forwarded headers](configuration.md#reverse-proxies), or rate limits will treat all clients as one.

To upgrade, run `./gradlew bootBuildImage` again, then `docker compose up -d`.

## Running as a service
If you're using a Linux distribution with systemd, you can use the `elysium.service` file to start the application on
boot. It runs the server as a dedicated `elysium` user, in a sandbox. As root:
```shell
# Create the user and group
useradd --system --user-group --home-dir /opt/elysium --shell /usr/sbin/nologin elysium

# Install the JAR. The server only needs to read it, so root keeps ownership.
install -d -m 755 /opt/elysium
install -m 644 build/libs/elysium.jar /opt/elysium/elysium.jar

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

The sandbox makes the filesystem read-only apart from private `/tmp` and `/var/tmp` directories and `/dev/shm`, which
isn't private. It also hides `/home`, `/root` and `/run/user`, so `java` and the JAR must be installed outside them. If
you need the server to write somewhere, or to listen on a port below 1024, the comments in `elysium.service` explain what
to change.
