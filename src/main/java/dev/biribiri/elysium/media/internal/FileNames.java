package dev.biribiri.elysium.media.internal;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The names of files in the media directory, which are only ever ones made here. A name read back from the database is
 * checked against these before it's used as a path, so that it can't reach outside the directory, and only these are
 * served, so that a file being written is never sent.
 */
public final class FileNames {

    /**
     * What each extension is served as. A browser is told not to guess, so this is the only type a file can be read as.
     * The servlet container's table is what's served, so {@code server.mime-mappings} has to agree with this.
     */
    public static final Map<String, String> CONTENT_TYPES = Map.of(
            "webp", "image/webp",
            "mp4", "video/mp4",
            "mov", "video/quicktime",
            "webm", "video/webm",
            "m4a", "audio/mp4",
            "mp3", "audio/mpeg",
            "ogg", "audio/ogg",
            "flac", "audio/flac",
            "wav", "audio/wav");

    private static final Pattern NAME =
            Pattern.compile("[0-9a-f-]{36}\\.(" + String.join("|", CONTENT_TYPES.keySet()) + ")");

    private FileNames() {
    }

    public static boolean isValid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static String contentType(String name) {
        return CONTENT_TYPES.get(name.substring(name.lastIndexOf('.') + 1));
    }
}
