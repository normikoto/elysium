package dev.biribiri.elysium.activitypub.resolve;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.inbound.LookedUpNotes;
import dev.biribiri.elysium.activitypub.inbound.RemoteActors;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteActorResolver.ResolveLimitException;
import dev.biribiri.elysium.content.ContentType;
import dev.biribiri.elysium.content.RemoteUrlResolver.Found;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteUrlLookupTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID REQUESTER = UUID.randomUUID();
    private static final URI ACTOR = URI.create("https://remote.example/users/bob");
    private static final URI NOTE = URI.create("https://remote.example/users/bob/statuses/1");

    private final ActorService actors = mock();
    private final RemoteActors remoteActors = mock();
    private final ActivityDocuments documents = mock();
    private final FederationHttpClient http = mock();
    private final DomainBlocks blocks = mock();
    private final StatusService statuses = mock();
    private final LookedUpNotes notes = mock();

    private RemoteUrlLookup lookup;
    private RemoteActorLookup actorLookup;

    @BeforeEach
    void setUp() {
        var instance = new InstanceProperties(URI.create("https://local.example"));
        var limits = new ResolveLimits();
        actorLookup = new RemoteActorLookup(actors, remoteActors, http, blocks, instance, JSON, limits);
        lookup = new RemoteUrlLookup(remoteActors, limits, notes, actors, statuses, documents, blocks, instance);
        when(actors.findByUri(any())).thenReturn(Optional.empty());
        when(actors.findRemoteByAcct(anyString(), anyString())).thenReturn(Optional.empty());
        when(statuses.findStoredByUrl(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void findsAStoredStatusWithoutAsking() throws Exception {
        var status = status();
        when(statuses.findStoredByUrl("https://remote.example/@bob/1")).thenReturn(Optional.of(status));

        assertThat(lookup.resolveUrl(REQUESTER, URI.create("https://remote.example/@bob/1")))
                .contains(new Found.Post(status));
        verify(documents, never()).fetch(any());
    }

    @Test
    void storesTheStatusAtAUrl() throws Exception {
        var page = URI.create("https://remote.example/@bob/1");
        var document = JSON.readTree("""
                {"id":"https://remote.example/users/bob/statuses/1","type":"Note"}
                """);
        when(documents.fetch(page)).thenReturn(document);
        var status = status();
        when(notes.store(page, document)).thenReturn(Optional.of(status));

        assertThat(lookup.resolveUrl(REQUESTER, page)).contains(new Found.Post(status));
        verify(documents, times(1)).fetch(any());
    }

    @Test
    void findsNothingWhenTheStatusCantBeStored() throws Exception {
        var document = JSON.readTree("""
                {"id":"https://remote.example/users/bob/statuses/1","type":"Note"}
                """);
        when(documents.fetch(NOTE)).thenReturn(document);
        when(notes.store(NOTE, document)).thenReturn(Optional.empty());

        assertThat(lookup.resolveUrl(REQUESTER, NOTE)).isEmpty();
        when(notes.store(NOTE, document)).thenThrow(new IllegalStateException("down"));
        assertThat(lookup.resolveUrl(REQUESTER, NOTE)).isEmpty();
    }

    @Test
    void findsNothingAtAUrlThatIsntAnActorOrAStatus() throws Exception {
        var other = URI.create("https://remote.example/things/1");
        when(documents.fetch(other)).thenReturn(JSON.readTree("""
                {"id":"https://remote.example/things/1","type":"Collection"}
                """));

        assertThat(lookup.resolveUrl(REQUESTER, other)).isEmpty();
        when(documents.fetch(other)).thenThrow(DocumentFetchException.gone("gone"));
        assertThat(lookup.resolveUrl(REQUESTER, other)).isEmpty();
        verify(notes, never()).store(any(), any());
    }

    @Test
    void findsTheActorAtAProfileUrlWithOneFetch() throws Exception {
        var profile = URI.create("https://remote.example/@bob");
        when(documents.fetch(profile)).thenReturn(JSON.readTree("""
                {"id":"https://remote.example/users/bob","type":"Person"}
                """));
        var bob = actor(Instant.now());
        when(remoteActors.fetchOrRefresh(ACTOR)).thenReturn(bob);

        assertThat(lookup.resolveUrl(REQUESTER, profile)).contains(new Found.Account(bob));
        verify(documents, times(1)).fetch(any());
        verify(notes, never()).store(any(), any());
    }

    @Test
    void usesARecentCopyOfAnActorWithoutAsking() throws Exception {
        var bob = actor(Instant.now());
        when(actors.findByUri(ACTOR)).thenReturn(Optional.of(bob));

        assertThat(lookup.resolveUrl(REQUESTER, ACTOR)).contains(new Found.Account(bob));
        verify(remoteActors, never()).fetchOrRefresh(any());
        verify(documents, never()).fetch(any());
    }

    @Test
    void usesAnOlderCopyOfAnActorWhenTheServerCantBeReached() throws Exception {
        var bob = actor(Instant.now().minus(Duration.ofDays(2)));
        when(actors.findByUri(ACTOR)).thenReturn(Optional.of(bob));
        when(remoteActors.fetchOrRefresh(ACTOR)).thenThrow(new IllegalStateException("down"));

        assertThat(lookup.resolveUrl(REQUESTER, ACTOR)).contains(new Found.Account(bob));
    }

    @Test
    void findsNothingForASuspendedActor() throws Exception {
        when(remoteActors.fetchOrRefresh(ACTOR)).thenThrow(new IllegalStateException("suspended"));
        for (var updatedAt : new Instant[]{Instant.now(), Instant.now().minus(Duration.ofDays(2))}) {
            when(actors.findByUri(ACTOR)).thenReturn(Optional.of(suspended(actor(updatedAt))));

            assertThat(lookup.resolveUrl(REQUESTER, ACTOR)).isEmpty();
        }
    }

    @Test
    void doesntAskThisServerOrABlockedOne() throws Exception {
        when(blocks.isBlocked("blocked.example")).thenReturn(true);

        assertThat(lookup.resolveUrl(REQUESTER, URI.create("https://blocked.example/@bob/1"))).isEmpty();
        assertThat(lookup.resolveUrl(REQUESTER, URI.create("https://local.example/users/alice/statuses/1"))).isEmpty();
        assertThat(lookup.resolveUrl(REQUESTER, URI.create("http://remote.example/@bob/1"))).isEmpty();
        verify(documents, never()).fetch(any());
        verify(statuses, never()).findStoredByUrl("https://blocked.example/@bob/1");
    }

    @Test
    void sharesItsLimitWithLookupsByAddress() throws Exception {
        when(http.get(any(), anyString(), any()))
                .thenReturn(new FederationHttpClient.Response(404, null, null, new byte[0], null));
        when(documents.fetch(any())).thenThrow(DocumentFetchException.gone("gone"));
        for (var i = 0; i < 15; i++) {
            actorLookup.resolveAcct(REQUESTER, "bob" + i, "remote.example");
            lookup.resolveUrl(REQUESTER, URI.create("https://remote.example/@bob/" + i));
        }

        assertThatThrownBy(() -> lookup.resolveUrl(REQUESTER, NOTE)).isInstanceOf(ResolveLimitException.class);
        assertThatThrownBy(() -> actorLookup.resolveAcct(REQUESTER, "bob", "remote.example"))
                .isInstanceOf(ResolveLimitException.class);
    }

    private static Status status() {
        return new Status(UUID.randomUUID(), NOTE, UUID.randomUUID(), "hi", "<p>hi</p>", null, null, Visibility.PUBLIC,
                false, false, null, null, null, null, Instant.now(), null, null, 0, null, null, false,
                ContentType.PLAIN);
    }

    private static Actor suspended(Actor actor) {
        return new Actor(
                actor.id(), actor.uri(), actor.username(), actor.domain(), actor.inboxUri(), actor.outboxUri(),
                actor.sharedInboxUri(), actor.publicKeyPem(), actor.createdAt(), actor.updatedAt(), Instant.now(), null);
    }

    private static Actor actor(Instant updatedAt) {
        return new Actor(
                UUID.randomUUID(), ACTOR, "bob", "remote.example", URI.create(ACTOR + "/inbox"), null, null, null,
                updatedAt, updatedAt);
    }
}
