package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.NewPoll;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.Poll;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Calls the handlers directly with no worker running, as {@link CreateIntegrationTests} does.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://polls.example", "elysium.jobs.worker-enabled=false"})
class PollIntegrationTests {

    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    @Autowired
    private CreateHandler create;
    @Autowired
    private UpdateHandler update;
    @Autowired
    private PollRefreshes refreshes;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private PollService polls;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @MockitoBean
    private ActivityDocuments documents;

    private Actor follower;
    private Actor author;
    private String host;
    private String followers;

    @BeforeEach
    void setUp() {
        follower = local();
        host = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/s");
        followers = uri + "/followers";
        author = actors.upsertRemote(new RemoteActorProfile(
                uri, "s", host, URI.create(uri + "/inbox"), null, null, null, URI.create(followers)));
        follow(follower, author);
    }

    @Test
    void storesAQuestionAsAPoll() {
        var id = noteId();
        create.handle(activity("Create", id, question(id, "oneOf", List.of("Yes", "No"), List.of(3, 4))));

        var poll = poll(id);
        assertThat(poll.local()).isFalse();
        assertThat(poll.multiple()).isFalse();
        assertThat(poll.titles()).containsExactly("Yes", "No");
        assertThat(poll.options()).extracting(Poll.Option::votesCount).containsExactly(3, 4);
        assertThat(poll.votersCount()).isEqualTo(7);
        assertThat(poll.expiresAt()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(poll.closedAt()).isNull();
    }

    @Test
    void readsAMultipleChoiceQuestionThatHasClosed() {
        var id = noteId();
        var question = question(id, "anyOf", List.of("Red", "Green", "Blue"), List.of(1, 2, 3));
        question.put("closed", "2026-01-01T00:00:00Z");
        question.put("votersCount", 4);
        create.handle(activity("Create", id, question));

        var poll = poll(id);
        assertThat(poll.multiple()).isTrue();
        assertThat(poll.votersCount()).isEqualTo(4);
        assertThat(poll.isExpired(Instant.now())).isTrue();
    }

    @Test
    void refreshesCountsFromAnUpdateThatIsNotAnEdit() {
        var id = noteId();
        create.handle(activity("Create", id, question(id, "oneOf", List.of("Yes", "No"), List.of(3, 4))));

        update.handle(activity("Update", id, question(id, "oneOf", List.of("Yes", "No"), List.of(5, 9))));

        assertThat(poll(id).options()).extracting(Poll.Option::votesCount).containsExactly(5, 9);
        assertThat(jdbc.sql("SELECT count(*) FROM status_edit WHERE status_id = :id").param("id", statusId(id))
                .query(Long.class).single()).isZero();
    }

    @Test
    void anEditOfTheOptionsResetsTheVotesFromHere() {
        var id = noteId();
        create.handle(activity("Create", id, question(id, "oneOf", List.of("Yes", "No"), List.of(3, 4))));
        var statusId = statusId(id);
        assertThat(polls.vote(statusId, follower.id(), List.of(0))).isPresent();
        assertThat(poll(id).options().getFirst().votesCount()).isEqualTo(4);

        var edited = question(id, "oneOf", List.of("Yes", "Maybe"), List.of(0, 0));
        edited.put("updated", "2026-06-01T00:00:00Z");
        update.handle(activity("Update", id, edited));

        assertThat(poll(id).titles()).containsExactly("Yes", "Maybe");
        assertThat(jdbc.sql("SELECT count(*) FROM poll_vote WHERE status_id = :id").param("id", statusId)
                .query(Long.class).single()).isZero();
        assertThat(statuses.findHistory(statuses.findStored(statusId).orElseThrow()).getFirst().pollOptions())
                .containsExactly("Yes", "No");
    }

    @Test
    void refreshesAPollFromItsServer() throws Exception {
        var id = noteId();
        create.handle(activity("Create", id, question(id, "oneOf", List.of("Yes", "No"), List.of(3, 4))));
        when(documents.fetch(URI.create(id)))
                .thenReturn(json.valueToTree(question(id, "oneOf", List.of("Yes", "No"), List.of(10, 20))));

        refreshes.run(new PollRefreshJob(statusId(id), Instant.now()));

        assertThat(poll(id).options()).extracting(Poll.Option::votesCount).containsExactly(10, 20);
        assertThat(polls.isStale(statusId(id), Instant.now())).isFalse();
    }

    @Test
    void countsAVoteInALocalPoll() {
        var poll = localPoll(Visibility.PUBLIC, false);
        var vote = vote(poll, "Coffee");

        create.handle(vote);
        create.handle(vote);

        assertThat(votes(poll)).containsExactly(1);
        assertThat(jdbc.sql("SELECT count(*) FROM status WHERE actor_id = :id").param("id", author.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void countsEachOptionOfAMultipleChoiceVoteOnce() {
        var poll = localPoll(Visibility.PUBLIC, true);
        create.handle(vote(poll, "Tea"));
        create.handle(vote(poll, "Coffee"));
        create.handle(vote(poll, "Coffee"));

        assertThat(votes(poll)).containsExactly(0, 1);
    }

    @Test
    void refusesVotesThatDoNotFit() {
        var poll = localPoll(Visibility.PUBLIC, false);
        create.handle(vote(poll, "Juice"));
        assertThat(votes(poll)).isEmpty();

        create.handle(vote(poll, "Tea"));
        create.handle(vote(poll, "Coffee"));
        assertThat(votes(poll)).containsExactly(0);

        var ended = localPoll(Visibility.PUBLIC, false);
        jdbc.sql("UPDATE poll SET expires_at = now() - interval '1 second' WHERE status_id = :id")
                .param("id", ended.id()).update();
        create.handle(vote(ended, "Tea"));
        assertThat(votes(ended)).isEmpty();
    }

    @Test
    void refusesAVoteFromSomeoneWhoCanNotSeeThePoll() {
        var poll = localPoll(Visibility.DIRECT, false);
        create.handle(vote(poll, "Tea"));
        assertThat(votes(poll)).isEmpty();
    }

    @Test
    void refusesAVoteAttributedToSomeoneElse() {
        var poll = localPoll(Visibility.PUBLIC, false);
        var vote = voteNote(poll, "Tea");
        vote.put("attributedTo", "https://" + host + "/users/other");
        create.handle(activity("Create", (String) vote.get("id"), vote));
        assertThat(votes(poll)).isEmpty();
    }

    private Status localPoll(Visibility visibility, boolean multiple) {
        return statuses.create(follower.id(), new NewStatus("Which?", null, null, visibility, false, null, List.of(),
                null, null, new NewPoll(List.of("Tea", "Coffee"), 600, multiple, false)));
    }

    private InboundActivity vote(Status poll, String name) {
        var note = voteNote(poll, name);
        return activity("Create", (String) note.get("id"), note);
    }

    private Map<String, Object> voteNote(Status poll, String name) {
        var note = new HashMap<String, Object>();
        note.put("id", author.uri() + "#votes/" + UUID.randomUUID());
        note.put("type", "Note");
        note.put("attributedTo", author.uri().toString());
        note.put("to", List.of(follower.uri().toString()));
        note.put("name", name);
        note.put("inReplyTo", poll.uri().toString());
        return note;
    }

    private List<Integer> votes(Status poll) {
        return jdbc.sql("SELECT position FROM poll_vote WHERE status_id = :id ORDER BY position")
                .param("id", poll.id())
                .query(Integer.class)
                .list();
    }

    private Map<String, Object> question(String id, String property, List<String> options, List<Integer> counts) {
        var question = new HashMap<String, Object>();
        question.put("id", id);
        question.put("type", "Question");
        question.put("attributedTo", author.uri().toString());
        question.put("to", List.of(PUBLIC));
        question.put("cc", List.of(followers));
        question.put("content", "<p>Which?</p>");
        question.put("published", "2026-01-01T00:00:00Z");
        question.put("endTime", "2030-01-01T00:00:00Z");
        question.put(property, java.util.stream.IntStream.range(0, options.size())
                .mapToObj(i -> Map.of("type", "Note", "name", options.get(i),
                        "replies", Map.of("type", "Collection", "totalItems", counts.get(i))))
                .toList());
        return question;
    }

    private InboundActivity activity(String type, String objectId, Map<String, Object> object) {
        var id = objectId + "/" + type.toLowerCase() + "/" + UUID.randomUUID();
        var body = "{\"id\":\"%s\",\"type\":\"%s\",\"actor\":\"%s\",\"object\":%s}"
                .formatted(id, type, author.uri(), json.writeValueAsString(object));
        return new InboundActivity(id, author.uri().toString(), type, body);
    }

    private Poll poll(String uri) {
        return polls.find(statusId(uri), Optional.empty()).orElseThrow();
    }

    private UUID statusId(String uri) {
        return jdbc.sql("SELECT id FROM status WHERE uri = :uri").param("uri", uri).query(UUID.class).single();
    }

    private String noteId() {
        return "https://" + host + "/notes/" + UUID.randomUUID();
    }

    private Actor local() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return actors.createLocalIfAbsent(name, actor -> {
        }).orElseThrow();
    }

    private void follow(Actor from, Actor to) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :follower, :followed, 'accepted', :activity)
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", from.id())
                .param("followed", to.id())
                .param("activity", from.uri() + "#follows/" + UUID.randomUUID())
                .update();
    }
}
