package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestImages;
import dev.biribiri.elysium.TestOAuth;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.CustomEmoji;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.media.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://packs.example",
        "elysium.jobs.worker-enabled=false",
        "elysium.custom-emojis.max-pack-files=5"
})
@AutoConfigureMockMvc
class EmojiPacksIntegrationTests {

    private static final String PAGE = "/admin/custom-emojis";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String ADMIN = "packadmin";
    private static final String USER = "packuser";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private CustomEmojis emojis;
    @Autowired
    private EmojiPacks packs;
    @Autowired
    private StatusService statuses;
    @Autowired
    private MediaProperties media;
    @Autowired
    private JsonMapper json;

    @TempDir
    private Path directory;

    @BeforeEach
    void setUp() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        ensureAccount(ADMIN);
        ensureAccount(USER);
        TestAccounts.makeAdmin(jdbc, actors.findLocalByUsername(ADMIN).orElseThrow().id());
    }

    @Test
    void importsAMisskeyPackOnThePageAndSkipsOrReplacesWhatExists() throws Exception {
        var session = TestOAuth.logIn(mockMvc, ADMIN, PASSWORD);
        var blob = shortcode();
        var cat = shortcode();
        var pack = zip(Map.of(
                "meta.json", misskeyMeta(blob, "Blobs", cat, null),
                blob + ".png", TestImages.png(32, 32),
                cat + ".png", TestImages.png(32, 32)));

        upload(session, pack, null, false)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Added 2, replaced 0, skipped 0")));
        assertThat(find(blob).category()).isEqualTo("Blobs");
        // A Misskey emoji with no category isn't given the pack's name, since Misskey's own import wouldn't.
        assertThat(find(cat).category()).isNull();

        var original = find(blob);
        var author = actors.findLocalByUsername(USER).orElseThrow();
        var post = statuses.create(author.id(), new NewStatus(":" + blob + ":", null, null, Visibility.PUBLIC, false,
                null));

        upload(session, pack, null, false)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Added 0, replaced 0, skipped 2")));
        assertThat(find(blob).imageName()).isEqualTo(original.imageName());

        upload(session, pack, "Imported", true)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Added 0, replaced 2, skipped 0")));
        var replaced = find(blob);
        assertThat(replaced.id()).isEqualTo(original.id());
        assertThat(replaced.category()).isEqualTo("Imported");
        assertThat(replaced.imageName()).isNotEqualTo(original.imageName());
        assertThat(media.directory().resolve(replaced.imageName())).exists();
        assertThat(media.directory().resolve(original.imageName())).doesNotExist();
        assertThat(emojis.findForStatus(post.id())).extracting(CustomEmoji::id).containsExactly(original.id());
    }

    @Test
    void anEmojiThatCantBeUsedDoesntStopTheRest() throws Exception {
        var session = TestOAuth.logIn(mockMvc, ADMIN, PASSWORD);
        var good = shortcode();
        var pack = zip(Map.of(
                good + ".png", TestImages.png(32, 32),
                "notanimage.png", "hello".getBytes(StandardCharsets.UTF_8),
                "x.png", TestImages.png(32, 32)));

        upload(session, pack, null, false)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Added 1, replaced 0, skipped 0 that already existed, and couldn&#39;t import 2")));
        // The pack's name is the uploaded file's.
        assertThat(find(good).category()).isEqualTo("party");
    }

    @Test
    void refusesAPackWithTooManyFiles() throws Exception {
        var session = TestOAuth.logIn(mockMvc, ADMIN, PASSWORD);
        var files = new HashMap<String, byte[]>();
        for (var i = 0; i < 6; i++) {
            files.put(shortcode() + ".png", TestImages.png(8, 8));
        }

        upload(session, zip(files), null, false)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("more than 5 files")));
    }

    @Test
    void exportsOnePackThatMisskeyAndAkkomaCanBothRead() throws Exception {
        var session = TestOAuth.logIn(mockMvc, ADMIN, PASSWORD);
        var blob = emojis.addLocal(shortcode(), TestImages.png(32, 32), "Blobs");
        var hidden = emojis.addLocal(shortcode(), TestImages.animatedGif(2, 16, 16), null);
        emojis.updateLocal(hidden.id(), null, false);

        var zip = mockMvc.perform(get(PAGE + "/export").session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", containsString("packs.example-emojis.zip")))
                .andReturn().getResponse().getContentAsByteArray();
        var files = unzip(zip);

        var meta = json.readTree(files.get("meta.json"));
        var pack = json.readTree(files.get("pack.json"));
        for (var record : meta.path("emojis")) {
            assertThat(record.path("downloaded").booleanValue()).isTrue();
            assertThat(record.path("fileName").stringValue()).matches("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9]+)*$");
            assertThat(files).containsKey(record.path("fileName").stringValue());
        }
        assertThat(entry(meta, blob.shortcode()).path("emoji").path("category").stringValue()).isEqualTo("Blobs");
        assertThat(entry(meta, hidden.shortcode())).isNotNull();
        assertThat(pack.path("files").path(blob.shortcode()).stringValue()).isEqualTo(blob.shortcode() + ".webp");
        assertThat(pack.path("files_count").intValue()).isEqualTo(pack.path("files").size());
        assertThat(files.get(blob.shortcode() + ".webp")).isEqualTo(emojis.readImage(blob));

        // Importing it again, as another server running this would, keeps the categories.
        emojis.deleteLocal(blob.id());
        var archive = directory.resolve("export.zip");
        Files.write(archive, zip);
        // Other tests' emoji are in it too, so it's past this class's limit on files.
        var result = packs.importPack(archive, "export.zip", null, false, false);
        assertThat(result.added()).containsExactly(blob.shortcode());
        assertThat(find(blob.shortcode()).category()).isEqualTo("Blobs");
    }

    @Test
    void onlyAdminsCanImportAndExport() throws Exception {
        var session = TestOAuth.logIn(mockMvc, USER, PASSWORD);
        mockMvc.perform(get(PAGE + "/export").session(session)).andExpect(status().isForbidden());
        upload(session, zip(Map.of("a.png", TestImages.png(8, 8))), null, false).andExpect(status().isForbidden());
    }

    @Test
    void theCommandsImportAndExport() throws Exception {
        var shortcode = shortcode();
        var archive = directory.resolve("cli.zip");
        Files.write(archive, zip(Map.of(shortcode + ".png", TestImages.png(16, 16))));

        var importer = new ImportEmojisCommand();
        importer.wire(packs);
        var out = new StringWriter();
        assertThat(run(importer, out, archive.toString(), "--category", "Command")).isZero();
        assertThat(out.toString()).contains("Added 1");
        assertThat(find(shortcode).category()).isEqualTo("Command");

        var exporter = new ExportEmojisCommand();
        exporter.wire(packs);
        var exported = directory.resolve("out.zip");
        assertThat(run(exporter, new StringWriter(), exported.toString())).isZero();
        assertThat(unzip(Files.readAllBytes(exported))).containsKey(shortcode + ".webp");
        assertThat(run(exporter, new StringWriter(), exported.toString())).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions upload(MockHttpSession session, byte[] pack,
                                                                       String category, boolean replace)
            throws Exception {
        var request = multipart(PAGE + "/import")
                .file(new MockMultipartFile("pack", "party.zip", "application/zip", pack))
                .session(session).with(csrf());
        if (category != null) {
            request.param("category", category);
        }
        if (replace) {
            request.param("replace", "true");
        }
        return mockMvc.perform(request);
    }

    private static int run(Object command, StringWriter out, String... args) {
        return new CommandLine(command)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(out, true))
                .execute(args);
    }

    private static JsonNode entry(JsonNode meta, String shortcode) {
        for (var record : meta.path("emojis")) {
            if (record.path("emoji").path("name").stringValue().equals(shortcode)) {
                return record;
            }
        }
        return null;
    }

    private static byte[] misskeyMeta(String first, String firstCategory, String second, String secondCategory) {
        return """
                {"metaVersion": 2, "host": "misskey.example", "emojis": [
                  {"downloaded": true, "fileName": "%s.png", "emoji": {"name": "%s", "category": %s}},
                  {"downloaded": true, "fileName": "%s.png", "emoji": {"name": "%s", "category": %s}}
                ]}""".formatted(first, first, quoted(firstCategory), second, second, quoted(secondCategory))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        var files = new HashMap<String, byte[]>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                assertThat(entry.getName()).doesNotContain("/");
                assertThat(files.put(entry.getName(), in.readAllBytes())).isNull();
            }
        }
        return files;
    }

    private CustomEmoji find(String shortcode) {
        return emojis.findLocal(java.util.List.of(shortcode)).get(shortcode);
    }

    private static String shortcode() {
        return "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private void ensureAccount(String username) throws Exception {
        if (actors.findLocalByUsername(username).isEmpty()) {
            mockMvc.perform(post("/signup").with(csrf())
                            .param("invite", TestAccounts.invite(jdbc))
                            .param("username", username)
                            .param("password", PASSWORD)
                            .param("confirmPassword", PASSWORD))
                    .andExpect(status().is3xxRedirection());
        }
    }
}
