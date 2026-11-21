package dev.biribiri.elysium.actor;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdPageTests {

    private static final UUID MAX = UUID.randomUUID();
    private static final UUID SINCE = UUID.randomUUID();
    private static final UUID MIN = UUID.randomUUID();

    @Test
    void addsNothingForTheFirstPage() {
        var page = new IdPage(20, null, null, null);
        var parameters = new HashMap<String, Object>();

        assertThat(page.cursors("s.id", parameters)).isEmpty();
        assertThat(page.orderAndLimit("s.id", parameters)).isEqualTo(" ORDER BY s.id DESC LIMIT :limit");
        assertThat(parameters).containsOnlyKeys("limit").containsEntry("limit", 20);
    }

    @Test
    void choosesTheNewestSinceACursor() {
        var page = new IdPage(20, MAX, SINCE, null);
        var parameters = new HashMap<String, Object>();

        assertThat(page.cursors("s.id", parameters)).isEqualTo(" AND s.id < :maxId AND s.id > :after");
        assertThat(page.orderAndLimit("s.id", parameters)).endsWith("DESC LIMIT :limit");
        assertThat(parameters).containsEntry("maxId", MAX).containsEntry("after", SINCE);
        assertThat(page.newestFirst(List.of(3, 2, 1))).containsExactly(3, 2, 1);
    }

    @Test
    void minIdWinsAndStartsFromTheOldestButIsStillNewestFirst() {
        var page = new IdPage(20, null, SINCE, MIN);
        var parameters = new HashMap<String, Object>();

        assertThat(page.cursors("id", parameters)).isEqualTo(" AND id > :after");
        assertThat(page.orderAndLimit("id", parameters)).isEqualTo(" ORDER BY id ASC LIMIT :limit");
        assertThat(parameters).containsEntry("after", MIN);
        assertThat(page.newestFirst(List.of(1, 2, 3))).containsExactly(3, 2, 1);
    }

    @Test
    void refusesAPageWithNoRoom() {
        assertThatThrownBy(() -> new IdPage(0, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
