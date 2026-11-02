package dev.biribiri.elysium.content.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StatusIdsTests {

    @Test
    void generatesVersion7Ids() {
        var id = StatusIds.fromTimestamp(Instant.now());
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void startsWithTheTimestamp() {
        var timestamp = Instant.parse("2026-11-02T12:34:56.789Z");
        var id = StatusIds.fromTimestamp(timestamp);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(timestamp.toEpochMilli());
    }

    @Test
    void differsForTheSameTimestamp() {
        var timestamp = Instant.now();
        assertThat(StatusIds.fromTimestamp(timestamp)).isNotEqualTo(StatusIds.fromTimestamp(timestamp));
    }
}
