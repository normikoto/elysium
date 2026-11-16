package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.InvalidCustomEmojiException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmojiPackReaderTests {

    private static final long LIMIT = 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    private Path directory;

    @Test
    void readsMisskeysCategoriesAndSkipsWhatItCouldntDownload() throws IOException {
        var archive = zip(files(
                "meta.json", """
                        {"metaVersion": 2, "emojis": [
                          {"downloaded": true, "fileName": "blob.png", "emoji": {"name": "blob", "category": "Blobs"}},
                          {"downloaded": true, "fileName": "plain.png", "emoji": {"name": "plain", "category": null}},
                          {"downloaded": false, "fileName": "gone.png", "emoji": {"name": "gone"}},
                          {"downloaded": true, "emoji": {"name": "nofile"}}
                        ]}""",
                "blob.png", "b",
                "plain.png", "p",
                "pack.json", "{\"files\": {\"other\": \"blob.png\"}}"));

        try (var pack = EmojiPackReader.open(archive, "export.zip", LIMIT, JSON)) {
            assertThat(pack.hasCategories()).isTrue();
            assertThat(pack.entries()).containsExactly(
                    new EmojiPackReader.Entry("blob", "Blobs", "blob.png"),
                    new EmojiPackReader.Entry("plain", null, "plain.png"));
            assertThat(pack.problems()).extracting(EmojiPackReader.Problem::what).containsExactly("gone", "nofile");
            assertThat(pack.read(pack.entries().getFirst(), LIMIT)).isEqualTo("b".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void readsAkkomasPackInAFolderWithItsNameAsTheCategory() throws IOException {
        var archive = zip(files(
                "blobs/pack.json", """
                        {"pack": {"license": "Apache 2.0"}, "files": {"blobcat": "cats/blobcat.png", "blob": "./blob.png"}}""",
                "blobs/cats/blobcat.png", "c",
                "blobs/blob.png", "b"));

        try (var pack = EmojiPackReader.open(archive, "download.zip", LIMIT, JSON)) {
            assertThat(pack.name()).isEqualTo("blobs");
            assertThat(pack.hasCategories()).isFalse();
            assertThat(pack.entries()).containsExactly(
                    new EmojiPackReader.Entry("blobcat", null, "blobs/cats/blobcat.png"),
                    new EmojiPackReader.Entry("blob", null, "blobs/./blob.png"));
            assertThat(pack.read(pack.entries().get(1), LIMIT)).isEqualTo("b".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void namesImagesAfterTheirFilesWithoutAManifest() throws IOException {
        var archive = zip(files(
                "party_parrot.gif", "g",
                "nested/blob.PNG", "b",
                "README.md", "not an emoji",
                "__MACOSX/._blob.PNG", "resource fork"));

        try (var pack = EmojiPackReader.open(archive, "Party Pack.zip", LIMIT, JSON)) {
            assertThat(pack.name()).isEqualTo("Party Pack");
            assertThat(pack.entries()).containsExactly(
                    new EmojiPackReader.Entry("blob", null, "nested/blob.PNG"),
                    new EmojiPackReader.Entry("party_parrot", null, "party_parrot.gif"));
        }
    }

    @Test
    void readsATarGzWithLongNames() throws IOException {
        var longName = "a".repeat(120) + "/blob_" + "b".repeat(40) + ".png";
        var archive = tarGz(files(longName, "b", "../escape.png", "x"));

        try (var pack = EmojiPackReader.open(archive, "pack.tar.gz", LIMIT, JSON)) {
            assertThat(pack.name()).isEqualTo("a".repeat(120));
            assertThat(pack.entries()).extracting(EmojiPackReader.Entry::file).containsExactly(longName);
            assertThat(pack.read(pack.entries().getFirst(), LIMIT)).isEqualTo("b".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void refusesNamesThatReachOutsideTheArchive() throws IOException {
        var archive = zip(files(
                "pack.json", "{\"files\": {\"up\": \"../secret.png\", \"abs\": \"/etc/passwd\", \"ok\": \"ok.png\"}}",
                "../evil.png", "e",
                "ok.png", "o"));

        try (var pack = EmojiPackReader.open(archive, "pack.zip", LIMIT, JSON)) {
            assertThat(pack.source().files()).containsExactlyInAnyOrder("pack.json", "ok.png");
            for (var entry : pack.entries()) {
                if (!entry.shortcode().equals("ok")) {
                    assertThatThrownBy(() -> pack.read(entry, LIMIT)).isInstanceOf(InvalidCustomEmojiException.class);
                }
            }
        }
    }

    @Test
    void refusesAFileLargerThanTheLimitWhateverItClaims() throws IOException {
        var archive = zip(files("big.png", "x".repeat((int) LIMIT + 1)));

        try (var pack = EmojiPackReader.open(archive, "pack.zip", LIMIT, JSON)) {
            assertThatThrownBy(() -> pack.read(pack.entries().getFirst(), LIMIT))
                    .isInstanceOf(InvalidCustomEmojiException.class)
                    .hasMessageContaining("larger than");
        }
    }

    @Test
    void keepsTheFirstOfTwoEmojiWithOneShortcode() throws IOException {
        var archive = zip(files("one/blob.png", "1", "two/blob.png", "2"));

        try (var pack = EmojiPackReader.open(archive, "pack.zip", LIMIT, JSON)) {
            assertThat(pack.entries()).hasSize(1);
            assertThat(pack.problems()).extracting(EmojiPackReader.Problem::what).containsExactly("blob");
        }
    }

    @Test
    void refusesWhatIsntAnArchiveOrHasABrokenManifest() throws IOException {
        var text = directory.resolve("notes.txt");
        Files.writeString(text, "just some text");
        assertThatThrownBy(() -> EmojiPackReader.open(text, "notes.txt", LIMIT, JSON))
                .isInstanceOf(InvalidCustomEmojiException.class);

        var broken = zip(files("meta.json", "{not json"));
        assertThatThrownBy(() -> EmojiPackReader.open(broken, "pack.zip", LIMIT, JSON))
                .isInstanceOf(InvalidCustomEmojiException.class)
                .hasMessageContaining("meta.json");
    }

    @Test
    void normalizesNames() {
        assertThat(EmojiPackReader.normalize("a/./b//c.png")).contains("a/b/c.png");
        assertThat(EmojiPackReader.normalize("a\\b.png")).contains("a/b.png");
        assertThat(EmojiPackReader.normalize("/etc/passwd")).isEmpty();
        assertThat(EmojiPackReader.normalize("a/../b.png")).isEmpty();
        assertThat(EmojiPackReader.normalize("C:/b.png")).isEmpty();
        assertThat(EmojiPackReader.normalize("folder/")).isEmpty();
        assertThat(EmojiPackReader.normalize("._blob.png")).isEmpty();
    }

    private static Map<String, String> files(String... namesAndContents) {
        var files = new LinkedHashMap<String, String>();
        for (var i = 0; i < namesAndContents.length; i += 2) {
            files.put(namesAndContents[i], namesAndContents[i + 1]);
        }
        return files;
    }

    private Path zip(Map<String, String> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        var path = Files.createTempFile(directory, "pack", ".zip");
        Files.write(path, bytes.toByteArray());
        return path;
    }

    private Path tarGz(Map<String, String> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (var file : files.entrySet()) {
                var content = file.getValue().getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry(file.getKey(), true);
                entry.setSize(content.length);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        }
        var path = Files.createTempFile(directory, "pack", ".tar.gz");
        Files.write(path, bytes.toByteArray());
        return path;
    }
}
