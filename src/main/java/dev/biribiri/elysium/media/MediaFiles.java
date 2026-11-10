package dev.biribiri.elysium.media;

import dev.biribiri.elysium.media.internal.FileNames;
import dev.biribiri.elysium.media.internal.MediaSniffer;
import dev.biribiri.elysium.media.internal.WebpEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the files attached to posts. Images are re-encoded as WebP, which also removes their metadata. Video and audio
 * are kept as they were sent, once their first bytes show they're a format that's accepted, since there's nothing here
 * that could re-encode them.
 *
 * <p>A file is received into a temporary file before anything is decided about it, so that a large one is never held in
 * memory. Like {@link Images}, files aren't part of the database's transactions: a caller stores one before saving a
 * reference to it, and deletes it if that fails.
 */
@Service
public class MediaFiles {

    /**
     * The most pixels an image can have. Larger ones are refused, rather than scaled, because decoding them is what costs.
     */
    public static final long MAX_IMAGE_PIXELS = WebpEncoder.MAX_PIXELS;

    /**
     * What can be uploaded, as clients are told.
     */
    public static final List<String> CONTENT_TYPES = List.of(
            "image/png", "image/jpeg", "image/gif", "image/webp",
            "video/mp4", "video/quicktime", "video/webm",
            "audio/mp4", "audio/mpeg", "audio/ogg", "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav");

    private static final Logger log = LoggerFactory.getLogger(MediaFiles.class);

    private final Images images;

    MediaFiles(Images images) {
        this.images = images;
    }

    /**
     * Copies a file into the media directory, stopping as soon as it's larger than {@code maxBytes}.
     *
     * @throws InvalidMediaException if it's larger than {@code maxBytes}
     */
    public Received receive(InputStream in, long maxBytes) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(images.directory(), Images.TEMPORARY_PREFIX, Images.TEMPORARY_SUFFIX);
            var head = new byte[MediaSniffer.HEAD_LENGTH];
            var headLength = 0;
            var size = 0L;
            var buffer = new byte[64 * 1024];
            try (var out = Files.newOutputStream(temporary)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    size += read;
                    if (size > maxBytes) {
                        throw new InvalidMediaException("The file is larger than " + describe(DataSize.ofBytes(maxBytes)));
                    }
                    if (headLength < head.length) {
                        var copied = Math.min(read, head.length - headLength);
                        System.arraycopy(buffer, 0, head, headLength, copied);
                        headLength += copied;
                    }
                    out.write(buffer, 0, read);
                }
            }
            return new Received(temporary, size, Arrays.copyOf(head, headLength));
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temporary);
            if (e instanceof IOException io) {
                throw new UncheckedIOException(io);
            }
            throw (RuntimeException) e;
        }
    }

    /**
     * @param thumbnail a still to show for a video or audio, or null. An image is its own preview, so it can't have one
     * @throws InvalidMediaException if the file isn't an accepted format, is larger than its kind's limit, or can't be read
     */
    public StoredMedia store(Received file, Received thumbnail, MediaLimits limits) {
        var sniffed = MediaSniffer.sniff(file.head()).orElseThrow(() -> new InvalidMediaException(
                "The file has to be a PNG, JPEG, GIF or WebP image, an MP4, MOV or WebM video, "
                        + "or MP3, M4A, Ogg, FLAC or WAV audio"));
        var limit = limits.of(sniffed.kind());
        if (file.size() > limit.toBytes()) {
            throw new InvalidMediaException("The " + sniffed.kind().name().toLowerCase(Locale.ROOT) + " is larger than "
                    + describe(limit));
        }
        if (sniffed.kind() == MediaKind.IMAGE) {
            if (thumbnail != null) {
                throw new InvalidMediaException("An image can't have a thumbnail");
            }
            return storeImage(read(file));
        }
        var stored = new ArrayList<String>();
        try {
            WebpEncoder.Encoded preview = null;
            if (thumbnail != null) {
                if (thumbnail.size() > limits.image().toBytes()) {
                    throw new InvalidMediaException("The thumbnail is larger than " + describe(limits.image()));
                }
                preview = images.encoder().encode(read(thumbnail), ImageKind.ATTACHMENT_PREVIEW);
                stored.add(images.write(preview.still() == null ? preview.image() : preview.still(), "webp"));
            }
            var name = images.moveIn(file.path(), sniffed.extension());
            return new StoredMedia(sniffed.kind(), name, stored.isEmpty() ? null : stored.getFirst(),
                    FileNames.contentType(name), file.size(), null, null,
                    preview == null ? null : preview.width(), preview == null ? null : preview.height());
        } catch (IOException e) {
            stored.forEach(images::delete);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            stored.forEach(images::delete);
            throw e;
        }
    }

    private StoredMedia storeImage(byte[] bytes) {
        var encoded = images.encoder().encode(bytes, ImageKind.ATTACHMENT, ImageKind.ATTACHMENT_PREVIEW);
        var name = images.write(encoded.image(), "webp");
        try {
            var preview = images.write(encoded.preview(), "webp");
            return new StoredMedia(MediaKind.IMAGE, name, preview, "image/webp", encoded.image().length,
                    encoded.width(), encoded.height(), encoded.previewWidth(), encoded.previewHeight());
        } catch (RuntimeException e) {
            images.delete(name);
            throw e;
        }
    }

    /**
     * Safe to repeat, and for a file that's already gone.
     */
    public void delete(String name) {
        if (name != null) {
            images.delete(name);
        }
    }

    /**
     * Removes files left by a receive that never finished, such as when the server stopped partway.
     */
    public void removeStaleTemporaryFiles(Instant before) {
        try (var files = Files.list(images.directory())) {
            for (var file : files.toList()) {
                var name = file.getFileName().toString();
                if (name.startsWith(Images.TEMPORARY_PREFIX) && name.endsWith(Images.TEMPORARY_SUFFIX)
                        && Files.getLastModifiedTime(file).toInstant().isBefore(before)) {
                    deleteQuietly(file);
                }
            }
        } catch (IOException e) {
            log.warn("Couldn't look for temporary files to remove", e);
        }
    }

    /**
     * @return the type a stored file is served as
     */
    public static String contentTypeOf(String name) {
        return FileNames.contentType(name);
    }

    private static byte[] read(Received file) {
        try {
            return Files.readAllBytes(file.path());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String describe(DataSize limit) {
        return Images.describe(limit);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // It's found again by removeStaleTemporaryFiles.
        }
    }
}
