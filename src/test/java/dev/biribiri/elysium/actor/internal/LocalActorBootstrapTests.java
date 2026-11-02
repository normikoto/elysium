package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class LocalActorBootstrapTests {

    @Autowired
    private ActorRepository actors;

    @Autowired
    private InstanceProperties instance;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void warnsWhenANonLocalActorHasTheSameIdentity(CapturedOutput output) {
        var uri = instance.resolve("/users/orphan").toString();
        jdbc.sql("""
                        INSERT INTO actor (id, uri, username, domain, inbox_uri, outbox_uri, public_key_pem)
                        VALUES (:id, :uri, 'orphan', :domain, :uri, :uri, 'key')
                        """)
                .param("id", UUID.randomUUID())
                .param("uri", uri)
                .param("domain", instance.domain())
                .update();

        new LocalActorBootstrap(actors, instance, "orphan").run(new DefaultApplicationArguments());

        assertThat(actors.findLocal("orphan", instance.domain())).isEmpty();
        assertThat(output).contains("Could not create local actor " + uri);
    }
}
