---
paths:
  - "src/main/resources/application*.properties"
  - "application.properties.example"
  - ".env.example"
  - "compose.yaml"
  - "docs/configuration.md"
  - "src/main/java/dev/biribiri/elysium/**/*Properties.java"
---

# Configuration

- `src/main/resources/application.properties` holds defaults built into the JAR, and deployments only override what
  they change.
- New settings an operator would use also belong in `application.properties.example`, `docs/configuration.md`,
  `.env.example`, and the `environment` section of `compose.yaml`, which passes them to the container.
- `elysium.instance.base-url` is required, and actor URIs are derived from it.
