package dev.biribiri.elysium.content;

import dev.biribiri.elysium.media.Received;

/**
 * A downloaded file that hasn't been stored yet. Closing it removes it unless it's been stored, so a caller always closes
 * it.
 */
public final class ReceivedMedia implements AutoCloseable {

    final Received file;

    ReceivedMedia(Received file) {
        this.file = file;
    }

    public long size() {
        return file.size();
    }

    @Override
    public void close() {
        file.close();
    }
}
