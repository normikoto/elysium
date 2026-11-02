# Elysium

Experimental ActivityPub server using Java and Spring Boot. Aim is general compatibility with Mastodon and other common
AP implementations.

## Building
You'll need JDK 25 or later installed on your system. Run the following:
```shell
./gradlew bootJar
```

Copy the resulting JAR into a suitable location.

## Running
To run:
```shell
# Copy from src/main/resources/application.properties in this repo
export SPRING_CONFIG_LOCATION=/path/to/application.properties
export SPRING_PROFILES_ACTIVE=prod
java -jar elysium.jar
```

If you're using a Linux distribution with systemd, you can use the `elysium.service` file to start the application on
boot:
```shell
cp elysium.service /etc/systemd/system
# Optionally edit the service file to your needs...
systemctl daemon-reload
systemctl enable --now elysium.service
```