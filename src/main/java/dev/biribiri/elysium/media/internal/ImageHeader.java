package dev.biribiri.elysium.media.internal;

import dev.biribiri.elysium.media.InvalidImageException;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * What an image's header says about its size, read without decoding it. Decoding allocates for every pixel of every frame,
 * so a small file claiming to be huge has to be refused before that.
 *
 * <p>The format comes from the file's first bytes. What the client says it is isn't trusted.
 *
 * @param frames how many frames it has. Only GIF and WebP can have more than one.
 */
record ImageHeader(ImageFormat format, int width, int height, int frames) {

    enum ImageFormat {PNG, JPEG, GIF, WEBP}

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    boolean isAnimated() {
        return frames > 1;
    }

    long pixels() {
        return (long) width * height;
    }

    static ImageHeader read(byte[] bytes) {
        try {
            if (startsWith(bytes, PNG_SIGNATURE)) {
                return viaImageIo(ImageFormat.PNG, bytes);
            }
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
                return viaImageIo(ImageFormat.JPEG, bytes);
            }
            if (startsWith(bytes, "GIF87a".getBytes()) || startsWith(bytes, "GIF89a".getBytes())) {
                return gif(bytes);
            }
            if (bytes.length >= 12 && startsWith(bytes, "RIFF".getBytes())
                    && Arrays.equals(bytes, 8, 12, "WEBP".getBytes(), 0, 4)) {
                return webp(bytes);
            }
        } catch (IOException | RuntimeException e) {
            throw new InvalidImageException("The image can't be read", e);
        }
        throw new InvalidImageException("The image has to be a PNG, JPEG, GIF or WebP");
    }

    private static ImageHeader viaImageIo(ImageFormat format, byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("No reader for " + format);
            }
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new ImageHeader(format, reader.getWidth(0), reader.getHeight(0), 1);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Counting frames skips over each one's compressed data without decoding it. The size is the logical screen, which
     * is what frames are composed onto.
     */
    private static ImageHeader gif(byte[] bytes) throws IOException {
        if (bytes.length < 10) {
            throw new IOException("Truncated GIF");
        }
        var width = (bytes[6] & 0xFF) | (bytes[7] & 0xFF) << 8;
        var height = (bytes[8] & 0xFF) | (bytes[9] & 0xFF) << 8;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(input, false, true);
                return new ImageHeader(ImageFormat.GIF, width, height, reader.getNumImages(true));
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * A WebP is a RIFF file of chunks. An extended one ({@code VP8X}) gives the canvas size and has an {@code ANMF} chunk
     * for each frame. A simple one is a single {@code VP8} (lossy) or {@code VP8L} (lossless) bitstream, whose size is in
     * its first bytes. See <a href="https://developers.google.com/speed/webp/docs/riff_container">the container spec</a>.
     */
    private static ImageHeader webp(byte[] bytes) throws IOException {
        var position = 12;
        int width = -1;
        int height = -1;
        var frames = 0;
        while (position + 8 <= bytes.length) {
            var type = new String(bytes, position, 4, StandardCharsets.US_ASCII);
            var size = littleEndian(bytes, position + 4, 4);
            var data = position + 8;
            if (size < 0 || data + size > bytes.length) {
                throw new IOException("Truncated WebP chunk " + type);
            }
            switch (type) {
                case "VP8X" -> {
                    require(size >= 10, type);
                    width = 1 + littleEndian(bytes, data + 4, 3);
                    height = 1 + littleEndian(bytes, data + 7, 3);
                }
                case "VP8 " -> {
                    require(size >= 10, type);
                    if (width < 0) {
                        width = littleEndian(bytes, data + 6, 2) & 0x3FFF;
                        height = littleEndian(bytes, data + 8, 2) & 0x3FFF;
                    }
                    frames = Math.max(frames, 1);
                }
                case "VP8L" -> {
                    require(size >= 5 && bytes[data] == 0x2F, type);
                    if (width < 0) {
                        var bits = littleEndian(bytes, data + 1, 4);
                        width = 1 + (bits & 0x3FFF);
                        height = 1 + (bits >>> 14 & 0x3FFF);
                    }
                    frames = Math.max(frames, 1);
                }
                case "ANMF" -> frames++;
                default -> {
                }
            }
            // Chunks are padded to an even length.
            position = data + size + (size & 1);
        }
        if (width < 1 || height < 1 || frames < 1) {
            throw new IOException("WebP has no image");
        }
        return new ImageHeader(ImageFormat.WEBP, width, height, frames);
    }

    private static void require(boolean condition, String chunk) throws IOException {
        if (!condition) {
            throw new IOException("Malformed WebP chunk " + chunk);
        }
    }

    private static int littleEndian(byte[] bytes, int offset, int length) {
        var value = 0L;
        for (var i = length - 1; i >= 0; i--) {
            value = value << 8 | (bytes[offset + i] & 0xFF);
        }
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }
}
