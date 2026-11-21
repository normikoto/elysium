package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.internal.BoostRepository;
import dev.biribiri.elysium.content.internal.MediaAttachmentRepository;
import dev.biribiri.elysium.content.internal.PollRepository;
import dev.biribiri.elysium.content.internal.QuoteRepository;
import dev.biribiri.elysium.content.internal.StatusRepository;
import dev.biribiri.elysium.content.internal.StatusThreads;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteStatusesTests {

    private final StatusRepository repository = mock();
    private final ActorService actors = mock();

    private final RemoteStatuses service = new RemoteStatuses(
            repository,
            mock(StatusThreads.class),
            actors,
            mock(PlatformTransactionManager.class),
            mock(ApplicationEventPublisher.class),
            mock(BoostRepository.class),
            mock(MediaAttachmentRepository.class),
            mock(MediaAttachments.class),
            mock(QuoteRepository.class),
            mock(PollRepository.class),
            mock(CustomEmojis.class));

    @Test
    void assignsABackfilledIdWhileHoldingTheLock() {
        var author = remoteAuthor();

        var id = service.storeBackfilled(remote(author, Instant.now().minusSeconds(1))).orElseThrow();

        // Otherwise one dated close to now could commit ahead of a status that was given an earlier ID.
        var order = inOrder(repository);
        order.verify(repository).lockIdAssignment();
        order.verify(repository).insert(any());
        assertThat(id.getMostSignificantBits() >>> 16).isLessThanOrEqualTo(Instant.now().toEpochMilli());
    }

    @Test
    void keepsABackfilledIdBetween1970AndNow() {
        var author = remoteAuthor();

        var early = service.storeBackfilled(remote(author, Instant.parse("1969-12-31T23:59:59Z"))).orElseThrow();
        var ancient = service.storeBackfilled(remote(author, Instant.MIN)).orElseThrow();
        var before = Instant.now().toEpochMilli();
        var future = service.storeBackfilled(remote(author, Instant.parse("3000-01-01T00:00:00Z"))).orElseThrow();

        // A negative time would set the ID's top bit, so that it sorted after every other status.
        assertThat(early.getMostSignificantBits()).isNotNegative();
        assertThat(early.getMostSignificantBits() >>> 16).isZero();
        assertThat(ancient.getMostSignificantBits() >>> 16).isZero();
        assertThat(future.getMostSignificantBits() >>> 16).isBetween(before, Instant.now().toEpochMilli());
    }

    private Actor remoteAuthor() {
        var author = actor("bob", "remote.example");
        when(actors.findById(author.id())).thenReturn(Optional.of(author));
        when(repository.insert(any())).thenReturn(true);
        return author;
    }

    private static RemoteStatus remote(Actor author, Instant published) {
        return new RemoteStatus(URI.create(author.uri() + "/statuses/" + UUID.randomUUID()), null, author.id(),
                "<p>old</p>", null, null, Visibility.PUBLIC, false, null, published);
    }

    private static Actor actor(String username, String domain) {
        var uri = URI.create("https://" + domain + "/users/" + username);
        return new Actor(UUID.randomUUID(), uri, username, domain, URI.create(uri + "/inbox"), null, null, "key",
                Instant.now(), Instant.now());
    }
}
