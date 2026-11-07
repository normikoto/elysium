package dev.biribiri.elysium.media;

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
import java.util.regex.Pattern;

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

    private static final Logger log = LoggerFactory.getLogger(Images.class);

    /**
     * Only names this class makes, so that a name read back from the database can't reach outside the directory.
     */
    private static final Pattern NAME = Pattern.compile("[0-9a-f-]{36}\\.webp");

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
        if (upload.length > limit.toBytes()) {
            throw new InvalidImageException("The image is larger than "
                    + (limit.toKilobytes() % 1024 == 0 ? limit.toMegabytes() + " MB" : limit.toKilobytes() + " KB"));
        }
        var encoded = encoder.encode(upload, kind);
        var name = write(encoded.image());
        if (encoded.still() == null) {
            return new StoredImage(name, name);
        }
        try {
            return new StoredImage(name, write(encoded.still()));
        } catch (RuntimeException e) {
            delete(name);
            throw e;
        }
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

    /**
     * Written to a temporary file in the same directory, then moved, so that a file is never served half written.
     */
    private String write(byte[] bytes) {
        var name = UUID.randomUUID() + ".webp";
        var directory = properties.directory();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, ".upload-", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            return name;
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

    private void delete(String name) {
        if (!NAME.matcher(name).matches()) {
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
