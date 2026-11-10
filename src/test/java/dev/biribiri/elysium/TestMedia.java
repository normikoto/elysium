package dev.biribiri.elysium;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The start of video and audio files, which is all that's checked of them, padded to look like a whole file.
 */
public final class TestMedia {

    private TestMedia() {
    }

    public static byte[] mp4(int size) {
        return pad(new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0,
                'i', 's', 'o', 'm', 'i', 's', 'o', '2'}, size);
    }

    public static byte[] m4a(int size) {
        return pad(new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 2, 0,
                'M', '4', 'A', ' ', 'm', 'p', '4', '2'}, size);
    }

    public static byte[] mp3(int size) {
        return pad("ID3".getBytes(StandardCharsets.US_ASCII), size);
    }

    public static byte[] html() {
        return "<!doctype html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pad(byte[] head, int size) {
        return Arrays.copyOf(head, Math.max(size, head.length));
    }
}
