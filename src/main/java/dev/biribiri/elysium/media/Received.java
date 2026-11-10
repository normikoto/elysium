package dev.biribiri.elysium.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A file that's been received into the media directory, but isn't stored yet. Closing it removes it, unless it's been
 * stored, so a caller always closes it.
 */
public final class Received implements AutoCloseable {

    private final Path path;
    private final long size;
    private final byte[] head;

    Received(Path path, long size, byte[] head) {
        this.path = path;
        this.size = size;
        this.head = head;
    }

    public long size() {
        return size;
    }

    Path path() {
        return path;
    }

    byte[] head() {
        return Arrays.copyOf(head, head.length);
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // MediaFiles.removeStaleTemporaryFiles removes it later.
        }
    }
}
