package dev.biribiri.elysium.actor;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTests {

    @Test
    void parsesIdsInEitherCase() {
        var id = UUID.randomUUID();

        assertThat(Ids.parse(id.toString())).contains(id);
        assertThat(Ids.parse(id.toString().toUpperCase())).contains(id);
    }

    @Test
    void returnsEmptyForAnythingElse() {
        for (var value : new String[]{null, "", "nonsense", "1-1-1-1-1"}) {
            assertThat(Ids.parse(value)).isEmpty();
        }
    }
}
