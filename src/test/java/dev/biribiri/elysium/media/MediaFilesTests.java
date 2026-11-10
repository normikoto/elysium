package dev.biribiri.elysium.media;

import dev.biribiri.elysium.TestImages;
import dev.biribiri.elysium.TestMedia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaFilesTests {

    private static final MediaLimits LIMITS =
            new MediaLimits(DataSize.ofKilobytes(200), DataSize.ofKilobytes(100), DataSize.ofKilobytes(50));

    @TempDir
    Path directory;

    private MediaFiles files;

    @BeforeEach
    void setUp() throws IOException {
        files = new MediaFiles(new Images(new MediaProperties(directory)));
    }

    @Test
    void reencodesAnImageAndMakesAPreviewThatKeepsItsShape() throws IOException {
        try (var received = receive(TestImages.png(1600, 900))) {
            var stored = files.store(received, null, LIMITS);

            assertThat(stored.kind()).isEqualTo(MediaKind.IMAGE);
            assertThat(stored.contentType()).isEqualTo("image/webp");
            assertThat(stored.name()).endsWith(".webp");
            assertThat(stored.width()).isEqualTo(1600);
            assertThat(stored.height()).isEqualTo(900);
            assertThat(stored.previewWidth()).isEqualTo(640);
            assertThat(stored.previewHeight()).isEqualTo(360);
            assertThat(directory.resolve(stored.previewName())).exists();
        }
        assertThat(temporaryFiles()).isZero();
    }

    @Test
    void keepsVideoAsItWasSentUnderItsOwnExtension() throws IOException {
        var bytes = TestMedia.mp4(1000);
        try (var received = receive(bytes)) {
            var stored = files.store(received, null, LIMITS);

            assertThat(stored.kind()).isEqualTo(MediaKind.VIDEO);
            assertThat(stored.contentType()).isEqualTo("video/mp4");
            assertThat(stored.name()).endsWith(".mp4");
            assertThat(stored.previewName()).isNull();
            assertThat(Files.readAllBytes(directory.resolve(stored.name()))).isEqualTo(bytes);
        }
        assertThat(temporaryFiles()).isZero();
    }

    @Test
    void storesAThumbnailForAudio() throws IOException {
        try (var received = receive(TestMedia.mp3(1000)); var thumbnail = receive(TestImages.png(100, 100))) {
            var stored = files.store(received, thumbnail, LIMITS);

            assertThat(stored.kind()).isEqualTo(MediaKind.AUDIO);
            assertThat(stored.previewName()).endsWith(".webp");
        }
    }

    @Test
    void appliesEachKindsLimit() throws IOException {
        try (var received = receive(TestMedia.mp3(60 * 1024))) {
            assertThatThrownBy(() -> files.store(received, null, LIMITS))
                    .isInstanceOf(InvalidMediaException.class)
                    .hasMessage("The audio is larger than 50 KB");
        }
        try (var received = receive(TestMedia.mp4(60 * 1024))) {
            assertThat(files.store(received, null, LIMITS).kind()).isEqualTo(MediaKind.VIDEO);
        }
    }

    @Test
    void stopsReceivingAtTheLimitAndLeavesNothingBehind() {
        assertThatThrownBy(() -> files.receive(new ByteArrayInputStream(new byte[2048]), 1024))
                .isInstanceOf(InvalidMediaException.class);
        assertThat(temporaryFiles()).isZero();
    }

    @Test
    void refusesWhatIsntAcceptedAndRemovesItWhenClosed() throws IOException {
        try (var received = receive(TestMedia.html())) {
            assertThatThrownBy(() -> files.store(received, null, LIMITS)).isInstanceOf(InvalidMediaException.class);
        }
        assertThat(temporaryFiles()).isZero();
    }

    @Test
    void onlyDeletesNamesItMakes() throws IOException {
        var outside = Files.writeString(directory.resolve("keep.txt"), "x");

        files.delete("keep.txt");
        files.delete("../keep.txt");

        assertThat(outside).exists();
    }

    @Test
    void removesOnlyStaleTemporaryFiles() throws IOException {
        var stale = Files.createFile(directory.resolve(".upload-1.tmp"));
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(3600)));
        var fresh = Files.createFile(directory.resolve(".upload-2.tmp"));

        files.removeStaleTemporaryFiles(Instant.now().minusSeconds(60));

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
    }

    private Received receive(byte[] bytes) {
        return files.receive(new ByteArrayInputStream(bytes), LIMITS.largest().toBytes());
    }

    private long temporaryFiles() {
        try (var listed = Files.list(directory)) {
            return listed.filter(file -> file.getFileName().toString().endsWith(".tmp")).count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
