package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorDataRemoval;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteProfile;
import dev.biribiri.elysium.content.FeaturedTag;
import dev.biribiri.elysium.content.FeaturedTags;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.RemoteStatuses;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reading the hashtags that accounts on other servers feature, from their {@code featuredTags} collections. The worker
 * doesn't run, so each test runs the job itself, and the actor's server is a mock.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://elysium.example", "elysium.jobs.worker-enabled=false"})
class FeaturedTagFetchIntegrationTests {

    @Autowired FeaturedTagFetch fetch;
    @Autowired AddHandler adds;
    @Autowired UpdateHandler updates;
    @Autowired ActorService actors;
    @Autowired FeaturedTags featuredTags;
    @Autowired RemoteStatuses remoteStatuses;
    @Autowired List<ActorDataRemoval> removals;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @MockitoBean ActivityDocuments documents;
    @MockitoSpyBean TargetFetchTurns turns;

    private Actor remote;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = 'featured-tag-fetch'").update();
        remote = remote();
    }

    @Test
    void replacesTheTagsWithWhatTheCollectionListsKeepingTheirIds() throws Exception {
        featuredTags.replaceRemote(remote.id(), List.of("kept", "dropped"));
        var kept = idOf("kept");
        remoteStatuses.storeRemote(new RemoteStatus(URI.create(remote.uri() + "/statuses/" + UUID.randomUUID()), null,
                remote.id(), "<p>#Kept</p>", null, null, Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(),
                List.of("kept")));
        serve(featuredTagsUri(), collection(List.of(hashtag("#Kept"), hashtag("#Café"))));

        runToTheEnd(job());

        assertThat(featuredTags.featured(remote.id())).extracting(FeaturedTag::name).containsExactly("kept", "café");
        assertThat(idOf("kept")).isEqualTo(kept);
        assertThat(featuredTags.featured(remote.id()).getFirst().statusesCount()).isOne();
        verify(turns).take(eq(featuredTagsUri()), any(), any());
        verify(documents, never()).fetchAs(any(), any());
    }

    @Test
    void readsOnlyHashtagsWithValidNamesUpToTheLimit() throws Exception {
        var items = new ArrayList<Object>(List.of(
                Map.of("type", "Note", "name", "#note"), hashtag("#not a tag"), Map.of("type", "Hashtag")));
        IntStream.range(0, FeaturedTags.MAX_PER_ACCOUNT).forEach(i -> items.add(hashtag("#t" + i)));
        serve(featuredTagsUri(), collection(items));

        runToTheEnd(job());

        assertThat(featuredTags.featured(remote.id())).hasSize(FeaturedTags.MAX_PER_ACCOUNT - 3);
    }

    @Test
    void readsAFirstPageOnTheActorsServer() throws Exception {
        var page = URI.create(featuredTagsUri() + "?page=1");
        var collection = new LinkedHashMap<String, Object>();
        collection.put("id", featuredTagsUri().toString());
        collection.put("type", "OrderedCollection");
        collection.put("first", page.toString());
        serve(featuredTagsUri(), collection);
        serve(page, Map.of("id", page.toString(), "type", "OrderedCollectionPage",
                "orderedItems", List.of(hashtag("#paged"))));

        runToTheEnd(job());

        assertThat(featuredTags.featured(remote.id())).extracting(FeaturedTag::name).containsExactly("paged");
    }

    @Test
    void refusesACollectionWithAnotherId() throws Exception {
        featuredTags.replaceRemote(remote.id(), List.of("kept"));
        var collection = collection(List.of(hashtag("#other")));
        collection.put("id", remote.uri() + "/collections/other");
        serve(featuredTagsUri(), collection);

        runToTheEnd(job());

        assertThat(featuredTags.featured(remote.id())).extracting(FeaturedTag::name).containsExactly("kept");
    }

    @Test
    void anActorWithoutACollectionFeaturesNone() throws Exception {
        featuredTags.replaceRemote(remote.id(), List.of("gone"));
        actors.upsertRemote(new RemoteActorProfile(remote.uri(), "someone", remote.domain(), remote.inboxUri(), null,
                null, null, null, null));

        runToTheEnd(job());

        assertThat(featuredTags.featured(remote.id())).isEmpty();
        verify(documents, never()).fetch(any());
    }

    @Test
    void anAddOfAHashtagToAFeaturedCollectionQueuesAFetch() {
        adds.handle(addOfHashtag(remote.uri() + "/collections/other"));
        assertThat(jobs()).isZero();

        adds.handle(addOfHashtag(remote.uri() + "/collections/featured"));
        assertThat(jobs()).isOne();
        assertThat(featuredTags.featured(remote.id())).isEmpty();
    }

    @Test
    void theTagsGoWhenTheirServerIsBlocked() {
        featuredTags.replaceRemote(remote.id(), List.of("blocked"));

        removals.forEach(removal -> removal.removeForDomain(remote.domain()));

        assertThat(featuredTags.featured(remote.id())).isEmpty();
    }

    private void runToTheEnd(FeaturedTagFetchJob job) {
        for (var runs = 0; job != null && runs < 3; runs++) {
            ExecutionComplete complete = mock();
            ExecutionOperations<FeaturedTagFetchJob> operations = mock();
            var rescheduled = new ArrayList<FeaturedTagFetchJob>();
            doAnswer(invocation -> rescheduled.add(invocation.getArgument(2)))
                    .when(operations).reschedule(any(), any(), any());
            fetch.run(job).complete(complete, operations);
            job = rescheduled.isEmpty() ? null : rescheduled.getFirst();
        }
    }

    private FeaturedTagFetchJob job() {
        return FeaturedTagFetchJob.of(remote.id().toString(), Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private UUID idOf(String name) {
        return featuredTags.featured(remote.id()).stream().filter(tag -> tag.name().equals(name)).findFirst()
                .orElseThrow().id();
    }

    private Actor remote() {
        var host = "ftf" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/someone");
        return actors.upsertRemote(new RemoteActorProfile(uri, "someone", host, URI.create(uri + "/inbox"),
                URI.create(uri + "/outbox"), null, null, URI.create(uri + "/followers"),
                URI.create(uri + "/collections/featured"), URI.create(uri + "/collections/tags"),
                RemoteProfile.EMPTY));
    }

    private URI featuredTagsUri() {
        return URI.create(remote.uri() + "/collections/tags");
    }

    /**
     * As Mastodon sends one when a tag is featured, with no {@code id}.
     */
    private InboundActivity addOfHashtag(String target) {
        var body = Map.of("type", "Add", "actor", remote.uri().toString(), "target", target,
                "object", hashtag("#added"));
        var id = remote.uri() + "#adds/" + UUID.randomUUID();
        return new InboundActivity(id, remote.uri().toString(), "Add", json.writeValueAsString(body), Instant.now());
    }

    /**
     * As Mastodon serves it, with its items and no pages.
     */
    private Map<String, Object> collection(List<Object> items) {
        var collection = new LinkedHashMap<String, Object>();
        collection.put("id", featuredTagsUri().toString());
        collection.put("type", "OrderedCollection");
        collection.put("totalItems", items.size());
        collection.put("orderedItems", items);
        return collection;
    }

    private Map<String, Object> hashtag(String name) {
        return Map.of("type", "Hashtag", "href", remote.uri() + "/tagged/" + name.substring(1), "name", name);
    }

    private void serve(URI uri, Map<String, Object> document) throws DocumentFetchException {
        when(documents.fetch(eq(uri))).thenReturn(json.readTree(json.writeValueAsString(document)));
    }

    private long jobs() {
        return jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_name = 'featured-tag-fetch'")
                .query(Long.class).single();
    }
}
