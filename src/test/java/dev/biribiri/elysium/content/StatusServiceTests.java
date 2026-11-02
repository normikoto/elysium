package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.internal.StatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatusServiceTests {

    private static final UUID AUTHOR = UUID.randomUUID();
    private static final URI BASE_URL = URI.create("https://elysium.example");

    private final StatusRepository repository = mock();
    private final ActorService actors = mock();

    private StatusService service;

    @BeforeEach
    void setUp() {
        when(actors.findLocalById(AUTHOR)).thenReturn(Optional.of(new Actor(
                AUTHOR, BASE_URL.resolve("/users/alice"), "alice", "elysium.example", BASE_URL.resolve("/users/alice/inbox"),
                BASE_URL.resolve("/users/alice/outbox"), null, "key", Instant.now())));
        service = new StatusService(
                repository,
                actors,
                new InstanceProperties(BASE_URL),
                new StatusProperties(10),
                mock(PlatformTransactionManager.class));
    }

    private static NewStatus draft(String text, String spoilerText) {
        return new NewStatus(text, spoilerText, null, Visibility.PUBLIC, false, null);
    }

    @Test
    void acceptsTextAtTheConfiguredLimit() {
        assertThat(service.create(AUTHOR, draft("a".repeat(10), null)).text()).hasSize(10);
    }

    @Test
    void rejectsTextOverTheConfiguredLimit() {
        assertThatThrownBy(() -> service.create(AUTHOR, draft("a".repeat(11), null)))
                .isInstanceOf(InvalidStatusException.class)
                .hasMessageContaining("10 characters");
        verify(repository, never()).insert(any());
    }

    @Test
    void countsTheContentWarningTowardTheLimit() {
        assertThat(service.create(AUTHOR, draft("a".repeat(5), "b".repeat(5))).sensitive()).isTrue();
        assertThatThrownBy(() -> service.create(AUTHOR, draft("a".repeat(5), "b".repeat(6))))
                .isInstanceOf(InvalidStatusException.class);
    }

    @Test
    void countsCodePointsRatherThanUtf16Units() {
        assertThat(service.create(AUTHOR, draft("😀".repeat(10), null)).text()).isEqualTo("😀".repeat(10));
    }

    @Test
    void normalizesLineEndingsAndSurroundingWhitespace() {
        var status = service.create(AUTHOR, draft("  a\r\nb\rc\n", null));
        assertThat(status.text()).isEqualTo("a\nb\nc");
        assertThat(status.content()).isEqualTo("<p>a<br>b<br>c</p>");
    }

    @Test
    void buildsTheUriFromTheAuthorAndId() {
        var status = service.create(AUTHOR, draft("hello", null));
        assertThat(status.uri()).isEqualTo(BASE_URL.resolve("/users/alice/statuses/" + status.id()));
    }

    @Test
    void rejectsValuesThatCannotBeStored() {
        for (var text : new String[]{"a\0b", "a\ud800b", "a\udc00b", "a\ud800"}) {
            assertThatThrownBy(() -> service.create(AUTHOR, draft(text, null)))
                    .isInstanceOf(InvalidStatusException.class);
            assertThatThrownBy(() -> service.create(AUTHOR, draft("ok", text)))
                    .isInstanceOf(InvalidStatusException.class);
        }
    }

    @Test
    void rejectsBlankText() {
        assertThatThrownBy(() -> service.create(AUTHOR, draft(" \n ", null)))
                .isInstanceOf(InvalidStatusException.class);
        assertThatThrownBy(() -> service.create(AUTHOR, draft(null, null)))
                .isInstanceOf(InvalidStatusException.class);
    }

    @Test
    void validatesTheLanguage() {
        var valid = new NewStatus("hi", null, "pt-BR", Visibility.PUBLIC, false, null);
        assertThat(service.create(AUTHOR, valid).language()).isEqualTo("pt-BR");
        var invalid = new NewStatus("hi", null, "not a language", Visibility.PUBLIC, false, null);
        assertThatThrownBy(() -> service.create(AUTHOR, invalid)).isInstanceOf(InvalidStatusException.class);
    }

    @Test
    void propertiesRejectLimitsBelowOne() {
        assertThatThrownBy(() -> new StatusProperties(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
