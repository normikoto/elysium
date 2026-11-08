package dev.biribiri.elysium.activitypub.resolve;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.inbound.RemoteActors;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteActorResolver.ResolveLimitException;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteActorLookupTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID REQUESTER = UUID.randomUUID();
    private static final URI ACTOR = URI.create("https://remote.example/users/bob");
    private static final URI WEBFINGER =
            URI.create("https://remote.example/.well-known/webfinger?resource=acct:bob@remote.example");

    private final ActorService actors = mock();
    private final RemoteActors remoteActors = mock();
    private final ActivityDocuments documents = mock();
    private final FederationHttpClient http = mock();
    private final DomainBlocks blocks = mock();

    private RemoteActorLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new RemoteActorLookup(
                actors, remoteActors, documents, http, blocks, new InstanceProperties(URI.create("https://local.example")),
                JSON);
        when(actors.findRemoteByAcct(anyString(), anyString())).thenReturn(Optional.empty());
        when(actors.findByUri(any())).thenReturn(Optional.empty());
    }

    @Test
    void findsAnAccountThroughWebFinger() throws Exception {
        answerWebFinger("""
                {"subject":"acct:bob@remote.example","links":[
                  {"rel":"http://webfinger.net/rel/profile-page","type":"text/html","href":"https://remote.example/@bob"},
                  {"rel":"self","type":"application/activity+json","href":"https://remote.example/users/bob"}]}
                """);
        var bob = actor(Instant.now());
        when(remoteActors.fetchOrRefresh(ACTOR)).thenReturn(bob);

        assertThat(lookup.resolveAcct(REQUESTER, "bob", "remote.example")).contains(bob);
    }

    @Test
    void usesARecentCopyWithoutAsking() throws Exception {
        var bob = actor(Instant.now());
        when(actors.findRemoteByAcct("bob", "remote.example")).thenReturn(Optional.of(bob));

        assertThat(lookup.resolveAcct(REQUESTER, "bob", "remote.example")).contains(bob);
        verify(http, never()).get(any(), any(), any());
    }

    @Test
    void findsAStoredActorWithoutAskingUnlessItsServerIsBlocked() throws Exception {
        var bob = actor(Instant.now().minus(Duration.ofDays(2)));
        when(actors.findRemoteByAcct("bob", "remote.example")).thenReturn(Optional.of(bob));

        assertThat(lookup.findStored("bob", "Remote.Example")).contains(bob);
        when(blocks.isBlocked("remote.example")).thenReturn(true);
        assertThat(lookup.findStored("bob", "remote.example")).isEmpty();
        verify(http, never()).get(any(), any(), any());
    }

    @Test
    void usesAnOlderCopyWhenTheServerCantBeReached() throws Exception {
        var bob = actor(Instant.now().minus(Duration.ofDays(2)));
        when(actors.findRemoteByAcct("bob", "remote.example")).thenReturn(Optional.of(bob));
        when(http.get(eq(WEBFINGER), anyString(), any())).thenThrow(new IOException("down"));

        assertThat(lookup.resolveAcct(REQUESTER, "bob", "remote.example")).contains(bob);
    }

    @Test
    void refusesAnActorWithADifferentAddress() throws Exception {
        var other = URI.create("https://trusted.example/users/admin");
        answerWebFinger("""
                {"subject":"acct:bob@remote.example","links":[
                  {"rel":"self","type":"application/activity+json","href":"https://trusted.example/users/admin"}]}
                """);
        var admin = new Actor(UUID.randomUUID(), other, "admin", "trusted.example", URI.create(other + "/inbox"), null, null,
                null, Instant.now(), Instant.now());
        when(remoteActors.fetchOrRefresh(other)).thenReturn(admin);
        when(http.get(eq(URI.create("https://trusted.example/.well-known/webfinger?resource=acct:admin@trusted.example")),
                anyString(), any())).thenReturn(jrd("""
                {"subject":"acct:admin@trusted.example","links":[
                  {"rel":"self","type":"application/activity+json","href":"https://trusted.example/users/admin"}]}
                """));

        assertThat(lookup.resolveAcct(REQUESTER, "bob", "remote.example")).isEmpty();
    }

    @Test
    void acceptsAnActorOnAnotherDomainWhoseServerGivesTheAddress() throws Exception {
        var social = URI.create("https://social.remote.example/users/bob");
        answerWebFinger("""
                {"subject":"acct:bob@remote.example","links":[
                  {"rel":"self","type":"application/activity+json","href":"https://social.remote.example/users/bob"}]}
                """);
        var bob = new Actor(UUID.randomUUID(), social, "bob", "social.remote.example", URI.create(social + "/inbox"), null,
                null, null, Instant.now(), Instant.now());
        when(remoteActors.fetchOrRefresh(social)).thenReturn(bob);
        var ownWebFinger = URI.create(
                "https://social.remote.example/.well-known/webfinger?resource=acct:bob@social.remote.example");
        when(http.get(eq(ownWebFinger), anyString(), any())).thenReturn(jrd("""
                {"subject":"acct:bob@remote.example","links":[
                  {"rel":"self","type":"application/activity+json","href":"https://social.remote.example/users/bob"}]}
                """));

        assertThat(lookup.resolveAcct(REQUESTER, "bob", "remote.example")).contains(bob);
    }

    @Test
    void usesAnOlderCopyByUrlWhenTheServerCantBeReached() throws Exception {
        var bob = actor(Instant.now().minus(Duration.ofDays(2)));
        when(actors.findByUri(ACTOR)).thenReturn(Optional.of(bob));
        when(remoteActors.fetchOrRefresh(ACTOR)).thenThrow(new IllegalStateException("down"));

        assertThat(lookup.resolveUrl(REQUESTER, ACTOR)).contains(bob);
    }

    @Test
    void ignoresLinksThatArentActivityPubDocuments() throws Exception {
        answerWebFinger("""
                {"links":[{"rel":"self","type":"text/html","href":"https://remote.example/users/bob"}]}
                """);

        assertThat(lookup.resolveAcct(REQUESTER, "bob", "remote.example")).isEmpty();
        verify(remoteActors, never()).fetchOrRefresh(any());
    }

    @Test
    void doesntAskThisServerOrABlockedOne() throws Exception {
        when(blocks.isBlocked("blocked.example")).thenReturn(true);

        assertThat(lookup.resolveAcct(REQUESTER, "bob", "local.example")).isEmpty();
        assertThat(lookup.resolveAcct(REQUESTER, "bob", "blocked.example")).isEmpty();
        assertThat(lookup.resolveAcct(REQUESTER, "bob", "blocked.example:8443")).isEmpty();
        assertThat(lookup.resolveUrl(REQUESTER, URI.create("https://blocked.example/@bob"))).isEmpty();
        assertThat(lookup.resolveUrl(REQUESTER, URI.create("https://local.example/users/alice"))).isEmpty();
        verify(http, never()).get(any(), any(), any());
        verify(documents, never()).fetch(any());
    }

    @Test
    void refusesWhatIsntAnAddress() throws Exception {
        assertThat(lookup.resolveAcct(REQUESTER, "bob/../x", "remote.example")).isEmpty();
        assertThat(lookup.resolveAcct(REQUESTER, "bob", "remote.example/path")).isEmpty();
        assertThat(lookup.resolveUrl(REQUESTER, URI.create("http://remote.example/@bob"))).isEmpty();
        verify(http, never()).get(any(), any(), any());
    }

    @Test
    void findsTheActorAtAProfileUrl() throws Exception {
        var profile = URI.create("https://remote.example/@bob");
        when(documents.fetch(profile)).thenReturn(JSON.readTree("""
                {"id":"https://remote.example/users/bob","type":"Person"}
                """));
        var bob = actor(Instant.now());
        when(remoteActors.fetchOrRefresh(ACTOR)).thenReturn(bob);

        assertThat(lookup.resolveUrl(REQUESTER, profile)).contains(bob);
    }

    @Test
    void findsNothingAtAUrlThatIsntAnActor() throws Exception {
        var note = URI.create("https://remote.example/notes/1");
        when(documents.fetch(note)).thenReturn(JSON.readTree("""
                {"id":"https://remote.example/notes/1","type":"Note"}
                """));

        assertThat(lookup.resolveUrl(REQUESTER, note)).isEmpty();
        when(documents.fetch(note)).thenThrow(DocumentFetchException.gone("gone"));
        assertThat(lookup.resolveUrl(REQUESTER, note)).isEmpty();
    }

    @Test
    void findsNothingForASuspendedActor() throws Exception {
        answerWebFinger("""
                {"links":[{"rel":"self","type":"application/activity+json","href":"https://remote.example/users/bob"}]}
                """);
        when(remoteActors.fetchOrRefresh(ACTOR)).thenThrow(new IllegalStateException("suspended"));

        for (var updatedAt : new Instant[]{Instant.now(), Instant.now().minus(Duration.ofDays(2))}) {
            var bob = suspended(actor(updatedAt));
            when(actors.findRemoteByAcct("bob", "remote.example")).thenReturn(Optional.of(bob));
            when(actors.findByUri(ACTOR)).thenReturn(Optional.of(bob));

            assertThat(lookup.resolveAcct(REQUESTER, "bob", "remote.example")).isEmpty();
            assertThat(lookup.resolveUrl(REQUESTER, ACTOR)).isEmpty();
        }
    }

    @Test
    void limitsHowOftenAnAccountCanAsk() throws Exception {
        when(http.get(any(), anyString(), any())).thenReturn(new FederationHttpClient.Response(404, null, null, new byte[0], null));
        for (var i = 0; i < 30; i++) {
            lookup.resolveAcct(REQUESTER, "bob" + i, "remote.example");
        }

        assertThatThrownBy(() -> lookup.resolveAcct(REQUESTER, "bob", "remote.example"))
                .isInstanceOf(ResolveLimitException.class);
        assertThat(lookup.resolveAcct(UUID.randomUUID(), "bob", "remote.example")).isEmpty();
    }

    private void answerWebFinger(String body) throws IOException {
        when(http.get(eq(WEBFINGER), anyString(), any())).thenReturn(jrd(body));
    }

    private static FederationHttpClient.Response jrd(String body) {
        return new FederationHttpClient.Response(200, "application/jrd+json", null, body.getBytes(StandardCharsets.UTF_8), null);
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
