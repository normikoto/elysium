package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.RemoteStatuses;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The FEP-f228 collection of a thread's posts, which the {@code context} of each of them names.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://contexts.example")
@AutoConfigureMockMvc
class ThreadCollectionIntegrationTests {

    private static final String ACTIVITY_JSON = "application/activity+json";

    @Autowired MockMvc mockMvc;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired RemoteStatuses remoteStatuses;
    @Autowired DomainBlocks blocks;

    @Test
    void namesTheCollectionOnlyOnStatusesThatItLists() throws Exception {
        var author = local();
        var root = post(author, null, Visibility.PUBLIC, false);
        var reply = post(author, root.id(), Visibility.UNLISTED, false);
        var hidden = post(author, root.id(), Visibility.PRIVATE, false);
        var underHidden = post(author, hidden.id(), Visibility.PUBLIC, false);
        var localOnly = post(author, root.id(), Visibility.PUBLIC, true);
        var remoteAuthor = remote();
        var remoteRoot = URI.create(remoteAuthor.uri() + "/statuses/" + UUID.randomUUID());
        remoteStatuses.storeRemote(new RemoteStatus(remoteRoot, null, remoteAuthor.id(), "<p>theirs</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now()));
        var toRemote = post(author, statuses.findStoredByUri(remoteRoot, remoteAuthor.id()).orElseThrow().id(),
                Visibility.PUBLIC, false);
        var context = root.uri() + "/context";

        fetch(root.uri()).andExpect(status().isOk()).andExpect(jsonPath("$.context").value(context));
        fetch(reply.uri()).andExpect(status().isOk()).andExpect(jsonPath("$.context").value(context));
        fetch(underHidden.uri()).andExpect(status().isOk()).andExpect(jsonPath("$.context").doesNotExist());
        fetch(toRemote.uri()).andExpect(status().isOk()).andExpect(jsonPath("$.context").doesNotExist());

        fetch(URI.create(context))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(context))
                .andExpect(jsonPath("$.type").value("OrderedCollection"))
                .andExpect(jsonPath("$.attributedTo").value(author.uri().toString()))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.first").value(context + "?page=true"));
        fetch(URI.create(context + "?page=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partOf").value(context))
                .andExpect(jsonPath("$.orderedItems", contains(root.uri().toString(), reply.uri().toString())))
                .andExpect(jsonPath("$.next").doesNotExist());
        // A reply has no collection of its own.
        fetch(URI.create(reply.uri() + "/context")).andExpect(status().isNotFound());
        fetch(URI.create(hidden.uri() + "/context")).andExpect(status().isNotFound());
        fetch(URI.create(localOnly.uri() + "/context")).andExpect(status().isNotFound());
    }

    @Test
    void listsRepliesFromOtherServersOldestFirstInPages() throws Exception {
        var author = local();
        var root = post(author, null, Visibility.PUBLIC, false);
        var listed = new ArrayList<String>(List.of(root.uri().toString()));
        var replier = remote();
        var parent = root.uri();
        for (var i = 0; i < ActivityPubController.COLLECTION_PAGE_SIZE; i++) {
            var uri = URI.create(replier.uri() + "/statuses/" + UUID.randomUUID());
            remoteStatuses.storeRemote(new RemoteStatus(uri, null, replier.id(), "<p>reply</p>", null, null,
                    Visibility.PUBLIC, false, parent, Instant.now()));
            listed.add(uri.toString());
            parent = uri;
        }
        var suspended = remote();
        var bySuspended = URI.create(suspended.uri() + "/statuses/" + UUID.randomUUID());
        remoteStatuses.storeRemote(new RemoteStatus(bySuspended, null, suspended.id(), "<p>gone</p>", null, null,
                Visibility.PUBLIC, false, root.uri(), Instant.now()));
        actors.suspend(suspended.id());
        var blocked = remote();
        var byBlocked = URI.create(blocked.uri() + "/statuses/" + UUID.randomUUID());
        remoteStatuses.storeRemote(new RemoteStatus(byBlocked, null, blocked.id(), "<p>blocked</p>", null, null,
                Visibility.PUBLIC, false, root.uri(), Instant.now()));
        blocks.create(blocked.domain(), null);
        var context = root.uri() + "/context";

        fetch(URI.create(context)).andExpect(jsonPath("$.totalItems").value(listed.size()));
        var last = statuses.findStoredByUri(URI.create(listed.get(listed.size() - 2)), replier.id()).orElseThrow();
        fetch(URI.create(context + "?page=true"))
                .andExpect(jsonPath("$.orderedItems", contains(listed.subList(0, listed.size() - 1).toArray())))
                .andExpect(jsonPath("$.next").value(context + "?page=true&min_id=" + last.id()));
        fetch(URI.create(context + "?page=true&min_id=" + last.id()))
                .andExpect(jsonPath("$.orderedItems", contains(listed.getLast())))
                .andExpect(jsonPath("$.next").doesNotExist());
        fetch(URI.create(context + "?page=true&min_id=nonsense")).andExpect(status().isBadRequest());
    }

    @Test
    void redirectsBrowsersToTheStatus() throws Exception {
        var root = post(local(), null, Visibility.PUBLIC, false);

        mockMvc.perform(get(root.uri().getRawPath() + "/context").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(root.uri().toString()));
    }

    private ResultActions fetch(URI uri) throws Exception {
        var path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        return mockMvc.perform(get(path).accept(ACTIVITY_JSON));
    }

    private Status post(Actor author, UUID inReplyTo, Visibility visibility, boolean localOnly) {
        return statuses.create(author.id(), new NewStatus("hello", null, null, visibility, false, inReplyTo, List.of(),
                null, null, null, localOnly));
    }

    private Actor local() {
        var username = "ctx" + UUID.randomUUID().toString().substring(0, 8);
        return actors.createLocalIfAbsent(username, actor -> {
        }).orElseThrow();
    }

    private Actor remote() {
        var host = "context" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/someone");
        return actors.upsertRemote(new RemoteActorProfile(uri, "someone", host, URI.create(uri + "/inbox"), null, null,
                null, URI.create(uri + "/followers")));
    }
}
