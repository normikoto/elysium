package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.AttachmentType;
import dev.biribiri.elysium.content.Focus;
import dev.biribiri.elysium.content.RemoteAttachment;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import java.time.Instant;
import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.media.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.net.URI;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uploading media, attaching it to statuses, and removing it. No job worker runs, so the cleanup that would run on a
 * schedule is called directly.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://media.example",
        "elysium.jobs.worker-enabled=false",
        "elysium.attachments.max-image-size=200KB",
        "elysium.attachments.max-video-size=100KB",
        "elysium.attachments.max-audio-size=50KB",
        "elysium.attachments.max-unattached-uploads=6"
})
@AutoConfigureMockMvc
class MediaIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";

    /**
     * Logging in is slow because of Argon2, so each account's token is only created once.
     */
    private static final Map<String, String> TOKENS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private MediaAttachments attachments;
    @Autowired
    private MediaProperties media;
    @Autowired
    private StatusService statuses;

    @BeforeEach
    void setUp() {
        TestAccounts.makeSureAnAccountExists(actors);
        // So that each test starts with no uploads waiting, and the cap on them doesn't carry over.
        jdbc.sql("UPDATE media_attachment SET removable_after = NULL WHERE status_id IS NULL").update();
        attachments.removeOrphans();
    }

    // Uploading

    @Test
    void uploadsAnImageAndServesItSafely() throws Exception {
        var upload = upload("mia", file("image.png", "image/png", TestImages.png(1600, 900)));
        mockMvc.perform(upload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("image"))
                .andExpect(jsonPath("$.url").value(startsWith("https://media.example/media/")))
                .andExpect(jsonPath("$.url").value(endsWith(".webp")))
                .andExpect(jsonPath("$.preview_url").value(endsWith(".webp")))
                .andExpect(jsonPath("$.remote_url").isEmpty())
                .andExpect(jsonPath("$.meta.original.width").value(1600))
                .andExpect(jsonPath("$.meta.original.size").value("1600x900"))
                .andExpect(jsonPath("$.meta.small.width").value(640))
                .andExpect(jsonPath("$.description").value("A test"))
                .andExpect(jsonPath("$.meta.focus.x").value(0.5));

        var url = JsonPath.<String>read(mockMvc.perform(upload("mia",
                file("image.png", "image/png", TestImages.png(10, 10)))).andReturn().getResponse().getContentAsString(), "$.url");
        mockMvc.perform(get(URI.create(url).getPath()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("sandbox")))
                .andExpect(header().string("Cache-Control", containsString("immutable")));
    }

    @Test
    void neverServesAFileThatsStillBeingReceived() throws Exception {
        var receiving = Files.writeString(media.directory().resolve(".upload-1234.tmp"), "<html>");
        try {
            mockMvc.perform(get("/media/.upload-1234.tmp")).andExpect(status().isNotFound());
            mockMvc.perform(get("/media/%2Eupload-1234.tmp")).andExpect(status().is4xxClientError());
        } finally {
            Files.deleteIfExists(receiving);
        }
    }

    @Test
    void keepsVideoAsItWasUploadedAndServesItInRanges() throws Exception {
        var response = mockMvc.perform(upload("mia", file("clip.bin", "application/octet-stream", TestMedia.mp4(2000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("video"))
                .andExpect(jsonPath("$.url").value(endsWith(".mp4")))
                .andExpect(jsonPath("$.preview_url").isEmpty())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get(URI.create(JsonPath.<String>read(response, "$.url")).getPath()).header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent())
                .andExpect(content().contentType("video/mp4"))
                .andExpect(header().string("Content-Range", "bytes 0-99/2000"));
    }

    @Test
    void acceptsAThumbnailForAudioButNotForAnImage() throws Exception {
        mockMvc.perform(upload("mia", file("song.mp3", "audio/mpeg", TestMedia.mp3(1000)))
                        .file(new MockMultipartFile("thumbnail", "t.png", "image/png", TestImages.png(100, 100))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("audio"))
                .andExpect(jsonPath("$.preview_url").value(endsWith(".webp")));

        mockMvc.perform(upload("mia", file("image.png", "image/png", TestImages.png(10, 10)))
                        .file(new MockMultipartFile("thumbnail", "t.png", "image/png", TestImages.png(10, 10))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void refusesWhatItsBytesDontShowItCanBe() throws Exception {
        mockMvc.perform(upload("mia", file("clip.mp4", "video/mp4", TestMedia.html())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("has to be")));
        mockMvc.perform(upload("mia", file("song.mp3", "audio/mpeg", TestMedia.mp3(60 * 1024))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("audio is larger than 50 KB")));
        mockMvc.perform(withToken(multipart("/api/v2/media"), "mia"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(upload("mia", file("a.png", "image/png", TestImages.png(10, 10))).param("focus", "nonsense"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void limitsHowManyUploadsCanWait() throws Exception {
        for (var i = 0; i < 6; i++) {
            mockMvc.perform(upload("cap", file("a.png", "image/png", TestImages.png(4, 4))))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(upload("cap", file("a.png", "image/png", TestImages.png(4, 4))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("waiting to be posted")));
    }

    @Test
    void needsTheMediaScope() throws Exception {
        var token = TestOAuth.userToken(mockMvc, account("mia"), PASSWORD, "read");
        mockMvc.perform(multipart("/api/v2/media").file(file("a.png", "image/png", TestImages.png(4, 4)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void theOldEndpointUploadsToo() throws Exception {
        mockMvc.perform(withToken(multipart("/api/v1/media").file(file("a.png", "image/png", TestImages.png(4, 4))), "mia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("image"));
    }

    // Viewing, changing, and deleting uploads

    @Test
    void onlyItsOwnerSeesAndChangesAnUpload() throws Exception {
        var id = uploadImage("mia");

        mockMvc.perform(withToken(get("/api/v1/media/" + id), "mia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mockMvc.perform(withToken(get("/api/v1/media/" + id), "max"))
                .andExpect(status().isNotFound());

        mockMvc.perform(withToken(put("/api/v1/media/" + id), "mia").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Changed\",\"focus\":\"-1,1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Changed"))
                .andExpect(jsonPath("$.meta.focus.x").value(-1.0));
        mockMvc.perform(withToken(put("/api/v1/media/" + id), "mia").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("description", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.meta.focus.y").value(1.0));
        mockMvc.perform(withToken(put("/api/v1/media/" + id), "max").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Mine now\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesAnUploadAndItsFilesButNotOnceItsAttached() throws Exception {
        var response = uploadResponse("mia");
        var id = JsonPath.<String>read(response, "$.id");

        mockMvc.perform(withToken(delete("/api/v1/media/" + id), "max")).andExpect(status().isNotFound());
        mockMvc.perform(withToken(delete("/api/v1/media/" + id), "mia")).andExpect(status().isOk());
        assertGone(JsonPath.read(response, "$.url"));
        assertGone(JsonPath.read(response, "$.preview_url"));

        var attached = uploadImage("mia");
        postStatus("mia", "{\"status\":\"\",\"media_ids\":[\"" + attached + "\"]}");
        mockMvc.perform(withToken(delete("/api/v1/media/" + attached), "mia"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(get("/api/v1/media/" + attached), "mia")).andExpect(status().isNotFound());
    }

    // Attaching to statuses

    @Test
    void attachesUploadsInOrderFromAForm() throws Exception {
        var first = uploadImage("mia");
        var second = uploadImage("mia");

        mockMvc.perform(withToken(post("/api/v1/statuses"), "mia").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Two pictures")
                        .param("media_ids[]", second, first)
                        .param("sensitive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensitive").value(true))
                .andExpect(jsonPath("$.media_attachments", hasSize(2)))
                .andExpect(jsonPath("$.media_attachments[0].id").value(second))
                .andExpect(jsonPath("$.media_attachments[1].id").value(first));
    }

    @Test
    void aStatusWithMediaCanHaveNoText() throws Exception {
        var id = uploadImage("mia");

        var status = postStatus("mia", "{\"media_ids\":[\"" + id + "\"]}");

        mockMvc.perform(get("/api/v1/statuses/" + status))
                .andExpect(jsonPath("$.content").value(""))
                .andExpect(jsonPath("$.media_attachments[0].id").value(id));
        mockMvc.perform(withToken(post("/api/v1/statuses"), "mia").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"media_ids\":[]}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void refusesMediaThatCantBeAttached() throws Exception {
        var others = uploadImage("max");
        refused("{\"status\":\"x\",\"media_ids\":[\"" + others + "\"]}");
        refused("{\"status\":\"x\",\"media_ids\":[\"not-an-id\"]}");

        var attached = uploadImage("mia");
        postStatus("mia", "{\"status\":\"first\",\"media_ids\":[\"" + attached + "\"]}");
        refused("{\"status\":\"again\",\"media_ids\":[\"" + attached + "\"]}");

        var images = List.of(uploadImage("mia"), uploadImage("mia"), uploadImage("mia"), uploadImage("mia"), uploadImage("mia"));
        refused("{\"status\":\"x\",\"media_ids\":" + json(images) + "}");
        refused("{\"status\":\"x\",\"media_ids\":" + json(List.of(images.get(0), images.get(0))) + "}");

        var video = JsonPath.<String>read(mockMvc.perform(upload("mia", file("v.mp4", "video/mp4", TestMedia.mp4(100))))
                .andReturn().getResponse().getContentAsString(), "$.id");
        refused("{\"status\":\"x\",\"media_ids\":" + json(List.of(images.get(1), video)) + "}");
        // None of them were attached by the statuses that were refused.
        postStatus("mia", "{\"status\":\"x\",\"media_ids\":" + json(images.subList(0, 4)) + "}");
    }

    @Test
    void anExpiredUploadCantBeAttached() throws Exception {
        var id = uploadImage("mia");
        jdbc.sql("UPDATE media_attachment SET removable_after = now() - interval '1 second' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();

        refused("{\"status\":\"x\",\"media_ids\":[\"" + id + "\"]}");
    }

    // Editing

    @Test
    void anEditReordersRemovesAndDescribesAttachments() throws Exception {
        var first = uploadResponse("mia");
        var second = uploadImage("mia");
        var third = uploadImage("mia");
        var firstId = JsonPath.<String>read(first, "$.id");
        var status = postStatus("mia", "{\"status\":\"x\",\"media_ids\":" + json(List.of(firstId, second)) + "}");

        mockMvc.perform(withToken(put("/api/v1/statuses/" + status), "mia").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"x\",\"media_ids\":" + json(List.of(third, second)) + ","
                                + "\"media_attributes\":[{\"id\":\"" + second + "\",\"description\":\"Second\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edited_at").isNotEmpty())
                .andExpect(jsonPath("$.media_attachments", hasSize(2)))
                .andExpect(jsonPath("$.media_attachments[0].id").value(third))
                .andExpect(jsonPath("$.media_attachments[1].description").value("Second"));
        // What an edit takes out goes at once, since it can't be posted again.
        assertGone(JsonPath.read(first, "$.url"));

        mockMvc.perform(get("/api/v1/statuses/" + status + "/history"))
                .andExpect(jsonPath("$[0].media_attachments", hasSize(0)))
                .andExpect(jsonPath("$[1].media_attachments", hasSize(2)));
    }

    @Test
    void anEditThatOnlyDescribesAnAttachmentIsAnEdit() throws Exception {
        var id = uploadImage("mia");
        var status = postStatus("mia", "{\"status\":\"x\",\"media_ids\":[\"" + id + "\"]}");

        mockMvc.perform(withToken(put("/api/v1/statuses/" + status), "mia").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "x")
                        .param("media_attributes[][id]", id)
                        .param("media_attributes[][description]", "Described"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edited_at").isNotEmpty())
                .andExpect(jsonPath("$.media_attachments[0].description").value("Described"));

        // Without media_ids, the attachments stay.
        mockMvc.perform(withToken(put("/api/v1/statuses/" + status), "mia").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"y\"}"))
                .andExpect(jsonPath("$.media_attachments", hasSize(1)));
    }

    // Deleting

    @Test
    void aDeletedStatusesMediaCanBePostedAgainForAWhile() throws Exception {
        var upload = uploadResponse("mia");
        var id = JsonPath.<String>read(upload, "$.id");
        var status = postStatus("mia", "{\"status\":\"x\",\"media_ids\":[\"" + id + "\"]}");

        mockMvc.perform(withToken(delete("/api/v1/statuses/" + status), "mia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("x"))
                .andExpect(jsonPath("$.media_attachments[0].id").value(id));
        attachments.removeOrphans();
        mockMvc.perform(get(URI.create(JsonPath.<String>read(upload, "$.url")).getPath())).andExpect(status().isOk());

        var redrafted = postStatus("mia", "{\"status\":\"x again\",\"media_ids\":[\"" + id + "\"]}");
        mockMvc.perform(withToken(delete("/api/v1/statuses/" + redrafted), "mia"))
                .andExpect(status().isOk());
        // Once the time to redraft is up.
        jdbc.sql("UPDATE media_attachment SET removable_after = now() - interval '1 second' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();
        attachments.removeOrphans();
        assertGone(JsonPath.read(upload, "$.url"));
    }

    @Test
    void deleteMediaRemovesTheFilesAtOnce() throws Exception {
        var upload = uploadResponse("mia");
        var status = postStatus("mia", "{\"status\":\"x\",\"media_ids\":[\"" + JsonPath.read(upload, "$.id") + "\"]}");

        mockMvc.perform(withToken(delete("/api/v1/statuses/" + status), "mia").param("delete_media", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media_attachments", hasSize(0)));

        assertGone(JsonPath.read(upload, "$.url"));
        assertGone(JsonPath.read(upload, "$.preview_url"));
    }

    @Test
    void anUploadThatIsntPostedInTimeIsRemoved() throws Exception {
        var upload = uploadResponse("mia");
        jdbc.sql("UPDATE media_attachment SET removable_after = now() - interval '1 second' WHERE id = :id")
                .param("id", UUID.fromString(JsonPath.read(upload, "$.id"))).update();

        attachments.removeOrphans();

        assertGone(JsonPath.read(upload, "$.url"));
    }

    @Test
    void removingAnAccountsMediaRemovesItsUploadsToo() throws Exception {
        var waiting = uploadResponse("gone");
        var posted = uploadResponse("gone");
        postStatus("gone", "{\"status\":\"x\",\"media_ids\":[\"" + JsonPath.read(posted, "$.id") + "\"]}");

        attachments.removeAllBy(actors.findLocalByUsername("gone").orElseThrow().id());

        assertGone(JsonPath.read(waiting, "$.url"));
        assertGone(JsonPath.read(posted, "$.url"));
    }

    // Listing

    @Test
    void onlyMediaListsStatusesWithMedia() throws Exception {
        var withMedia = postStatus("lister", "{\"status\":\"#mediatag pictured\",\"media_ids\":[\"" + uploadImage("lister") + "\"]}");
        postStatus("lister", "{\"status\":\"#mediatag not pictured\"}");
        var accountId = actors.findLocalByUsername("lister").orElseThrow().id();

        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/statuses").param("only_media", "true"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(withMedia));
        mockMvc.perform(get("/api/v1/timelines/tag/mediatag").param("only_media", "true"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/v1/timelines/public").param("only_media", "true").param("local", "true"))
                .andExpect(jsonPath("$[*].media_attachments[0]").isNotEmpty());
    }

    @Test
    void theInstanceGivesTheLimits() throws Exception {
        mockMvc.perform(get("/api/v1/instance"))
                .andExpect(jsonPath("$.configuration.statuses.max_media_attachments").value(4))
                .andExpect(jsonPath("$.configuration.media_attachments.image_size_limit").value(200 * 1024))
                .andExpect(jsonPath("$.configuration.media_attachments.video_size_limit").value(100 * 1024))
                .andExpect(jsonPath("$.configuration.media_attachments.supported_mime_types",
                        hasItems("image/png", "video/mp4", "audio/mpeg")));
    }

    // Federation

    @Test
    void aNoteListsItsAttachmentsAsDocuments() throws Exception {
        var upload = uploadResponse("mia");
        var status = postStatus("mia", "{\"status\":\"Look\",\"sensitive\":true,\"media_ids\":[\""
                + JsonPath.read(upload, "$.id") + "\"]}");

        var fetched = mockMvc.perform(get("/users/mia/statuses/" + status).accept("application/activity+json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensitive").value(true))
                .andExpect(jsonPath("$.attachment", hasSize(1)))
                .andExpect(jsonPath("$.attachment[0].type").value("Document"))
                .andExpect(jsonPath("$.attachment[0].mediaType").value("image/webp"))
                .andExpect(jsonPath("$.attachment[0].url").value(JsonPath.<String>read(upload, "$.url")))
                .andExpect(jsonPath("$.attachment[0].name").value("A test"))
                .andExpect(jsonPath("$.attachment[0].width").value(32))
                .andExpect(jsonPath("$.attachment[0].focalPoint[0]").value(0.5))
                .andExpect(jsonPath("$.attachment[0].blurhash").doesNotExist())
                .andExpect(jsonPath("$['@context'][1].focalPoint['@container']").value("@list"))
                .andExpect(jsonPath("$['@context'][1].toot").value("http://joinmastodon.org/ns#"))
                .andReturn().getResponse().getContentAsString();

        // The outbox builds the same Note, so one that's fetched matches one that's delivered.
        var page = mockMvc.perform(get("/users/mia/outbox").param("page", "true").accept("application/activity+json"))
                .andReturn().getResponse().getContentAsString();
        var fromOutbox = JsonPath.<List<Map<String, Object>>>read(page, "$.orderedItems[?(@.object.id =~ /.*" + status
                + "/)].object");
        var single = JsonPath.<Map<String, Object>>read(fetched, "$");
        single.remove("@context");
        assertThat(fromOutbox).containsExactly(single);

        var plain = postStatus("mia", "{\"status\":\"No media\"}");
        mockMvc.perform(get("/users/mia/statuses/" + plain).accept("application/activity+json"))
                .andExpect(jsonPath("$.attachment").doesNotExist());
    }

    @Test
    void aRemoteAttachmentLinksToItsServer() throws Exception {
        var host = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/s");
        var author = actors.upsertRemote(new RemoteActorProfile(uri, "s", host, URI.create(uri + "/inbox"), null, null,
                null, null));
        var image = URI.create("https://files." + host + "/a.jpg");
        var video = URI.create("https://files." + host + "/b.mp4");
        var id = statuses.storeRemote(new RemoteStatus(URI.create(uri + "/notes/1"), null, author.id(), "<p>Far</p>",
                null, null, Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of("faraway"), List.of(
                new RemoteAttachment(image, AttachmentType.IMAGE, "image/jpeg", "Far away", 1200, 800, null,
                        new Focus(0, 0)),
                new RemoteAttachment(video, AttachmentType.VIDEO, "video/mp4", null, null, null, null, null))))
                .orElseThrow();

        mockMvc.perform(get("/api/v1/statuses/" + id))
                .andExpect(jsonPath("$.media_attachments[0].type").value("image"))
                .andExpect(jsonPath("$.media_attachments[0].url").value(image.toString()))
                .andExpect(jsonPath("$.media_attachments[0].remote_url").value(image.toString()))
                .andExpect(jsonPath("$.media_attachments[0].preview_url").value(image.toString()))
                .andExpect(jsonPath("$.media_attachments[0].meta.original.size").value("1200x800"))
                .andExpect(jsonPath("$.media_attachments[0].description").value("Far away"))
                .andExpect(jsonPath("$.media_attachments[1].type").value("video"))
                .andExpect(jsonPath("$.media_attachments[1].preview_url").isEmpty());
        // Pages only link to another server's files, so that visitors' browsers don't fetch them unasked.
        mockMvc.perform(get("/tags/faraway").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"" + image + "\"")))
                .andExpect(content().string(not(containsString("src=\"" + image + "\""))));
    }

    @Test
    void pagesShowAttachments() throws Exception {
        var upload = uploadResponse("mia");
        var status = postStatus("mia", "{\"status\":\"On a page\",\"media_ids\":[\"" + JsonPath.read(upload, "$.id") + "\"]}");

        mockMvc.perform(get("/users/mia/statuses/" + status).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(URI.create(JsonPath.read(upload, "$.preview_url")).toString())))
                .andExpect(content().string(containsString("alt=\"A test\"")));
    }

    private void assertGone(String url) throws Exception {
        mockMvc.perform(get(URI.create(url).getPath())).andExpect(status().isNotFound());
    }

    private void refused(String body) throws Exception {
        mockMvc.perform(withToken(post("/api/v1/statuses"), "mia").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent());
    }

    private String postStatus(String username, String body) throws Exception {
        var response = mockMvc.perform(withToken(post("/api/v1/statuses"), username)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String uploadImage(String username) throws Exception {
        return JsonPath.read(uploadResponse(username), "$.id");
    }

    private String uploadResponse(String username) throws Exception {
        return mockMvc.perform(upload(username, file("a.png", "image/png", TestImages.png(32, 32))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private MockMultipartHttpServletRequestBuilder upload(String username, MockMultipartFile file) throws Exception {
        var builder = multipart("/api/v2/media").file(file);
        builder.param("description", "A test").param("focus", "0.5,-0.5");
        withToken(builder, username);
        return builder;
    }

    private static MockMultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("file", name, type, bytes);
    }

    private static String json(List<String> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").toList().toString().replace(" ", "");
    }

    private <T extends AbstractMockHttpServletRequestBuilder<T>> T withToken(T request, String username) throws Exception {
        return request.header("Authorization", "Bearer " + token(username));
    }

    private String account(String username) throws Exception {
        token(username);
        return username;
    }

    private String token(String username) throws Exception {
        var cached = TOKENS.get(username);
        if (cached != null) {
            return cached;
        }
        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, "read write");
        TOKENS.put(username, token);
        return token;
    }
}
