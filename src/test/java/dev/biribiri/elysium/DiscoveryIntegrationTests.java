package dev.biribiri.elysium;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://elysium.example")
@AutoConfigureMockMvc
class DiscoveryIntegrationTests {

    private static final String ACTOR_URI = "https://elysium.example/users/admin";
    private static final MediaType ACTIVITY_JSON = MediaType.parseMediaType("application/activity+json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ActorService actors;

    @Autowired
    private StatusService statuses;

    @BeforeEach
    void setUp() {
        createActor("admin");
    }

    @Test
    void webFingerFindsLocalActor() throws Exception {
        mockMvc.perform(get("/.well-known/webfinger").param("resource", "acct:admin@elysium.example"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/jrd+json"))
                .andExpect(jsonPath("$.subject").value("acct:admin@elysium.example"))
                .andExpect(jsonPath("$.links[0].rel").value("self"))
                .andExpect(jsonPath("$.links[0].type").value("application/activity+json"))
                .andExpect(jsonPath("$.links[0].href").value(ACTOR_URI))
                .andExpect(jsonPath("$.links[1].rel").value("http://webfinger.net/rel/profile-page"))
                .andExpect(jsonPath("$.links[1].type").value("text/html"))
                .andExpect(jsonPath("$.links[1].href").value(ACTOR_URI));
    }

    @Test
    void webFingerMatchesUsernameAndDomainCaseInsensitively() throws Exception {
        mockMvc.perform(get("/.well-known/webfinger").param("resource", "acct:ADMIN@ELYSIUM.example"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("acct:admin@elysium.example"));
    }

    @Test
    void webFingerRejectsMalformedResources() throws Exception {
        for (var resource : new String[]{"admin@elysium.example", "acct:admin", "acct:@elysium.example", "acct:admin@"}) {
            mockMvc.perform(get("/.well-known/webfinger").param("resource", resource))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/.well-known/webfinger")).andExpect(status().isBadRequest());
    }

    @Test
    void webFingerReturnsNotFoundForUnknownAccounts() throws Exception {
        mockMvc.perform(get("/.well-known/webfinger").param("resource", "acct:nobody@elysium.example"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/.well-known/webfinger").param("resource", "acct:admin@other.example"))
                .andExpect(status().isNotFound());
    }

    @Test
    void servesActorDocumentAsActivityJson() throws Exception {
        var body = mockMvc.perform(get("/users/admin").accept(ACTIVITY_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(ACTIVITY_JSON))
                .andExpect(jsonPath("$['@context'][0]").value("https://www.w3.org/ns/activitystreams"))
                .andExpect(jsonPath("$.id").value(ACTOR_URI))
                .andExpect(jsonPath("$.type").value("Person"))
                .andExpect(jsonPath("$.preferredUsername").value("admin"))
                .andExpect(jsonPath("$.url").value(ACTOR_URI))
                .andExpect(jsonPath("$.inbox").value(ACTOR_URI + "/inbox"))
                .andExpect(jsonPath("$.outbox").value(ACTOR_URI + "/outbox"))
                .andExpect(jsonPath("$.publicKey.id").value(ACTOR_URI + "#main-key"))
                .andExpect(jsonPath("$.publicKey.owner").value(ACTOR_URI))
                .andReturn().getResponse().getContentAsString();

        var pem = com.jayway.jsonpath.JsonPath.<String>read(body, "$.publicKey.publicKeyPem");
        assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----\n");
        var der = Base64.getMimeDecoder().decode(pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", ""));
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        assertThat(key.getModulus().bitLength()).isEqualTo(2048);
    }

    @Test
    void servesActorDocumentAsLdJson() throws Exception {
        mockMvc.perform(get("/users/admin")
                        .header("Accept", "application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\""))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/ld+json")))
                .andExpect(jsonPath("$.id").value(ACTOR_URI));
    }

    @Test
    void returnsNotFoundForUnknownActor() throws Exception {
        mockMvc.perform(get("/users/nobody").accept(ACTIVITY_JSON)).andExpect(status().isNotFound());
    }

    @Test
    void creatingAnExistingActorChangesNothing() throws Exception {
        var keyBefore = publicKey();

        assertThat(createActor("admin")).isEmpty();

        assertThat(jdbc.sql("SELECT count(*) FROM actor WHERE username = 'admin'").query(Long.class).single()).isOne();
        assertThat(publicKey()).isEqualTo(keyBefore);
    }

    @Test
    void rejectsUnauthenticatedRequestsToOtherPaths() throws Exception {
        // This path exists, so it would return 200 rather than 403 if it were left open.
        mockMvc.perform(get("/actuator")).andExpect(status().isForbidden());
    }

    @Test
    void webFingerFindsActorByUri() throws Exception {
        mockMvc.perform(get("/.well-known/webfinger").param("resource", ACTOR_URI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("acct:admin@elysium.example"));
    }

    @Test
    void webFingerTreatsTheAcctSchemeCaseInsensitively() throws Exception {
        mockMvc.perform(get("/.well-known/webfinger").param("resource", "ACCT:admin@elysium.example"))
                .andExpect(status().isOk());
    }

    @Test
    void webFingerReturnsNotFoundForWellFormedResourcesWithNoMatch() throws Exception {
        for (var resource : new String[]{"https://other.example/users/admin", "mailto:admin@elysium.example"}) {
            mockMvc.perform(get("/.well-known/webfinger").param("resource", resource))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void rejectsInvalidUsernamesWithoutQuerying() throws Exception {
        mockMvc.perform(get("/.well-known/webfinger").param("resource", "acct:a\u0000b@elysium.example"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/.well-known/webfinger").param("resource", "acct:not-valid@elysium.example"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/users/" + "a".repeat(31)).accept(ACTIVITY_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void allowsCrossOriginDiscovery() throws Exception {
        mockMvc.perform(get("/.well-known/webfinger")
                        .param("resource", "acct:admin@elysium.example")
                        .header("Origin", "https://client.example"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
        mockMvc.perform(get("/users/admin").accept(ACTIVITY_JSON).header("Origin", "https://client.example"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
        mockMvc.perform(options("/.well-known/webfinger")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }

    @Test
    void permitsHeadRequestsOnPublicPaths() throws Exception {
        mockMvc.perform(head("/users/admin").accept(ACTIVITY_JSON)).andExpect(status().isOk());
        mockMvc.perform(head("/.well-known/webfinger").param("resource", "acct:admin@elysium.example"))
                .andExpect(status().isOk());
    }

    @Test
    void nodeInfoWellKnownLinksToEachVersion() throws Exception {
        mockMvc.perform(get("/.well-known/nodeinfo").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.links.length()").value(2))
                .andExpect(jsonPath("$.links[0].rel").value("http://nodeinfo.diaspora.software/ns/schema/2.1"))
                .andExpect(jsonPath("$.links[0].href").value("https://elysium.example/nodeinfo/2.1"))
                .andExpect(jsonPath("$.links[1].rel").value("http://nodeinfo.diaspora.software/ns/schema/2.0"))
                .andExpect(jsonPath("$.links[1].href").value("https://elysium.example/nodeinfo/2.0"));
    }

    @Test
    void nodeInfoDescribesTheServer() throws Exception {
        var users = jdbc.sql("SELECT count(*) FROM local_account").query(Long.class).single();
        var posts = jdbc.sql("SELECT count(*) FROM status WHERE visibility <> 'direct'").query(Long.class).single();
        for (var version : new String[]{"2.0", "2.1"}) {
            mockMvc.perform(get("/nodeinfo/" + version).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().string(
                            "Content-Type",
                            containsString("profile=\"http://nodeinfo.diaspora.software/ns/schema/" + version + "#\"")))
                    .andExpect(jsonPath("$.version").value(version))
                    .andExpect(jsonPath("$.software.name").value("elysium"))
                    .andExpect(jsonPath("$.software.version").value(matchesPattern("\\d+\\.\\d+\\.\\d+.*")))
                    .andExpect(jsonPath("$.protocols[0]").value("activitypub"))
                    .andExpect(jsonPath("$.services.inbound").isEmpty())
                    .andExpect(jsonPath("$.services.outbound").isEmpty())
                    .andExpect(jsonPath("$.openRegistrations").value(false))
                    .andExpect(jsonPath("$.usage.users.total").value(users))
                    .andExpect(jsonPath("$.usage.localPosts").value(posts));
        }
    }

    @Test
    void nodeInfoCountsLocalPostsExceptDirectOnes() throws Exception {
        jdbc.sql("DELETE FROM status").update();
        var author = actors.findLocalByUsername("admin").orElseThrow().id();
        var parent = createStatus(author, Visibility.PUBLIC, null);
        var unlisted = createStatus(author, Visibility.UNLISTED, null);
        createStatus(author, Visibility.PRIVATE, null);
        createStatus(author, Visibility.DIRECT, null);
        createStatus(author, Visibility.PUBLIC, parent.id());
        expectLocalPosts(4);

        statuses.delete(unlisted.id(), author);
        expectLocalPosts(3);
    }

    @Test
    void nodeInfo21IncludesTheRepository() throws Exception {
        mockMvc.perform(get("/nodeinfo/2.1").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.software.repository").value("https://github.com/normikoto/elysium"));
    }

    @Test
    void nodeInfoMatchesThePublishedSchemas() throws Exception {
        for (var version : new String[]{"2.0", "2.1"}) {
            assertThat(nodeInfoSchema(version).validate(nodeInfo(version), InputFormat.JSON)).isEmpty();
        }
    }

    @Test
    void nodeInfoSchemasRejectDocumentsThatDoNotMatch() throws Exception {
        // This keeps the test above from passing if the schemas ever stop being enforced. With its version changed, the
        // 2.1 document only differs from the 2.0 one by having a repository, which the 2.0 schema doesn't allow.
        var document = nodeInfo("2.1").replace("\"version\":\"2.1\"", "\"version\":\"2.0\"");

        assertThat(nodeInfoSchema("2.0").validate(document, InputFormat.JSON))
                .singleElement()
                .satisfies(error -> assertThat(error.getKeyword()).isEqualTo("additionalProperties"));
    }

    @Test
    void nodeInfoCanBeReadFromAnyOrigin() throws Exception {
        for (var path : new String[]{"/.well-known/nodeinfo", "/nodeinfo/2.0", "/nodeinfo/2.1"}) {
            mockMvc.perform(get(path).header("Origin", "https://client.example"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"));
        }
    }

    @Test
    void permitsHealthProbes() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void frontPageLeavesOutSignUpWhenRegistrationIsDisabled() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/login\"")))
                .andExpect(content().string(not(containsString("href=\"/signup\""))));
    }

    private Status createStatus(UUID author, Visibility visibility, UUID inReplyToId) {
        return statuses.create(author, new NewStatus("Hello", null, null, visibility, false, inReplyToId));
    }

    private void expectLocalPosts(int count) throws Exception {
        for (var version : new String[]{"2.0", "2.1"}) {
            mockMvc.perform(get("/nodeinfo/" + version).accept(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.usage.localPosts").value(count));
        }
    }

    private String nodeInfo(String version) throws Exception {
        return mockMvc.perform(get("/nodeinfo/" + version).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Reads a copy of a schema from https://github.com/jhass/nodeinfo/tree/main/schemas, which is under CC0.
     */
    private static Schema nodeInfoSchema(String version) throws IOException {
        var schema = new ClassPathResource("nodeinfo/schema-" + version + ".json");
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_4)
                .getSchema(schema.getContentAsString(StandardCharsets.UTF_8), InputFormat.JSON);
    }

    private String publicKey() {
        return jdbc.sql("SELECT public_key_pem FROM actor WHERE username = 'admin'").query(String.class).single();
    }

    private Optional<Actor> createActor(String username) {
        return actors.createLocalIfAbsent(username, actor -> {
        });
    }
}
