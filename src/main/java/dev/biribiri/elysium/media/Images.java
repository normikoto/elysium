package dev.biribiri.elysium.media;

import dev.biribiri.elysium.media.internal.FileNames;
import dev.biribiri.elysium.media.internal.WebpEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Keeps uploaded images in the media directory. Every image is re-encoded as WebP and cropped to its kind's size, so what's
 * served is never the file that was uploaded.
 *
 * <p>Files aren't part of the database's transactions. A caller stores an image before saving a reference to it, and
 * deletes it if that fails, so a file can be left over but a reference can't point at nothing.
 */
@Service
public class Images {

    public static final String PATH = "/media/";

    static final String TEMPORARY_PREFIX = ".upload-";
    static final String TEMPORARY_SUFFIX = ".tmp";

    private static final Logger log = LoggerFactory.getLogger(Images.class);

    private final MediaProperties properties;
    private final WebpEncoder encoder = new WebpEncoder();

    Images(MediaProperties properties) throws IOException {
        this.properties = properties;
        Files.createDirectories(properties.directory());
    }

    /**
     * @param limit the largest file that can be stored, which depends on what it's for
     * @throws InvalidImageException if the file is too large, isn't a supported image, or can't be decoded
     */
    public StoredImage store(byte[] upload, ImageKind kind, DataSize limit) {
        checkSize(upload.length, limit);
        var encoded = encoder.encode(upload, kind);
        var name = write(encoded.image(), "webp");
        if (encoded.still() == null) {
            return new StoredImage(name, name);
        }
        try {
            return new StoredImage(name, write(encoded.still(), "webp"));
        } catch (RuntimeException e) {
            delete(name);
            throw e;
        }
    }

    /**
     * For a caller to refuse a file before reading it, with the same message as {@link #store} gives.
     *
     * @throws InvalidImageException if it's larger than the limit
     */
    public static void checkSize(long size, DataSize limit) {
        if (size > limit.toBytes()) {
            throw new InvalidImageException("The image is larger than " + describe(limit));
        }
    }

    /**
     * A limit as people are told it.
     */
    static String describe(DataSize limit) {
        return limit.toKilobytes() % 1024 == 0 ? limit.toMegabytes() + " MB" : limit.toKilobytes() + " KB";
    }

    /**
     * Safe to repeat, and for an image that's already gone.
     */
    public void delete(StoredImage image) {
        delete(image.name());
        delete(image.staticName());
    }

    /**
     * @return the path the image is served at, relative to the server's base URL
     */
    public static String pathOf(String name) {
        return PATH + name;
    }

    WebpEncoder encoder() {
        return encoder;
    }

    Path directory() {
        return properties.directory();
    }

    /**
     * Written to a temporary file in the same directory, then moved, so that a file is never served half written.
     */
    String write(byte[] bytes, String extension) {
        var directory = properties.directory();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX);
            Files.write(temporary, bytes);
            return moveIn(temporary, extension);
        } catch (IOException e) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param file a temporary file in the media directory, which is moved, so that it's never served half written
     */
    String moveIn(Path file, String extension) throws IOException {
        var name = UUID.randomUUID() + "." + extension;
        Files.move(file, properties.directory().resolve(name), StandardCopyOption.ATOMIC_MOVE);
        return name;
    }

    void delete(String name) {
        if (!FileNames.isValid(name)) {
            log.warn("Not deleting {}, which isn't the name of an image", name);
            return;
        }
        try {
            Files.deleteIfExists(properties.directory().resolve(name));
        } catch (IOException e) {
            // The image isn't referred to any more, so leaving it is only wasted space.
            log.warn("Couldn't delete image {}", name, e);
        }
    }
}
