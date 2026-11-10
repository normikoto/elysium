package dev.biribiri.elysium.media.internal;

import dev.biribiri.elysium.media.MediaKind;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Works out what a file is from its first bytes, out of the formats that are accepted. What a client or another server
 * says a file is isn't trusted: a file is only ever stored and served as what its bytes show it to be, so that an HTML
 * page sent as a video can't be served as either.
 *
 * <p>Video and audio aren't decoded, so a file that starts right can still be broken further on. That only stops it
 * playing.
 */
public final class MediaSniffer {

    /**
     * Enough for every signature here, including WebM's DocType, which follows the EBML header.
     */
    public static final int HEAD_LENGTH = 64;

    /**
     * @param contentType what the file was sent as. An image is served as WebP, once it's re-encoded
     */
    public record Sniffed(MediaKind kind, String contentType, String extension) {
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] EBML = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    private static final Set<String> AUDIO_MP4_BRANDS = Set.of("M4A ", "M4B ", "M4P ");
    private static final Set<String> VIDEO_MP4_BRANDS = Set.of(
            "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "dash", "M4V ", "MSNV");

    private MediaSniffer() {
    }

    public static Optional<Sniffed> sniff(byte[] head) {
        if (startsWith(head, 0, PNG)) {
            return image("image/png");
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return image("image/jpeg");
        }
        if (startsWith(head, 0, ascii("GIF87a")) || startsWith(head, 0, ascii("GIF89a"))) {
            return image("image/gif");
        }
        if (startsWith(head, 0, ascii("RIFF")) && startsWith(head, 8, ascii("WEBP"))) {
            return image("image/webp");
        }
        if (startsWith(head, 4, ascii("ftyp")) && head.length >= 12) {
            var brand = new String(head, 8, 4, StandardCharsets.US_ASCII);
            if (AUDIO_MP4_BRANDS.contains(brand)) {
                return found(MediaKind.AUDIO, "audio/mp4", "m4a");
            }
            if (brand.equals("qt  ")) {
                return found(MediaKind.VIDEO, "video/quicktime", "mov");
            }
            if (VIDEO_MP4_BRANDS.contains(brand)) {
                return found(MediaKind.VIDEO, "video/mp4", "mp4");
            }
            return Optional.empty();
        }
        if (startsWith(head, 0, EBML) && contains(head, ascii("webm"))) {
            return found(MediaKind.VIDEO, "video/webm", "webm");
        }
        if (startsWith(head, 0, ascii("OggS"))) {
            return found(MediaKind.AUDIO, "audio/ogg", "ogg");
        }
        if (startsWith(head, 0, ascii("fLaC"))) {
            return found(MediaKind.AUDIO, "audio/flac", "flac");
        }
        if (startsWith(head, 0, ascii("RIFF")) && startsWith(head, 8, ascii("WAVE"))) {
            return found(MediaKind.AUDIO, "audio/wav", "wav");
        }
        if (startsWith(head, 0, ascii("ID3")) || isMpegAudioFrame(head)) {
            return found(MediaKind.AUDIO, "audio/mpeg", "mp3");
        }
        return Optional.empty();
    }

    /**
     * An MP3 without an ID3 tag starts with a frame: 11 set bits, then a version and a layer, where a layer of 0 is
     * reserved. ADTS AAC uses layer 0, so it isn't mistaken for MP3.
     */
    private static boolean isMpegAudioFrame(byte[] head) {
        if (head.length < 2 || (head[0] & 0xFF) != 0xFF || (head[1] & 0xE0) != 0xE0) {
            return false;
        }
        var version = head[1] >> 3 & 0b11;
        var layer = head[1] >> 1 & 0b11;
        return version != 0b01 && layer != 0;
    }

    private static Optional<Sniffed> image(String contentType) {
        return found(MediaKind.IMAGE, contentType, "webp");
    }

    private static Optional<Sniffed> found(MediaKind kind, String contentType, String extension) {
        return Optional.of(new Sniffed(kind, contentType, extension));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        return bytes.length >= offset + prefix.length
                && Arrays.equals(bytes, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    private static boolean contains(byte[] bytes, byte[] part) {
        for (var i = 0; i + part.length <= bytes.length; i++) {
            if (startsWith(bytes, i, part)) {
                return true;
            }
        }
        return false;
    }
}
