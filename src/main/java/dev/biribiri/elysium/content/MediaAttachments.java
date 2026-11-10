package dev.biribiri.elysium.content;

import dev.biribiri.elysium.content.internal.MediaAttachmentRepository;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.media.InvalidMediaException;
import dev.biribiri.elysium.media.MediaFiles;
import dev.biribiri.elysium.media.MediaKind;
import dev.biribiri.elysium.media.StoredMedia;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Files attached to statuses, and uploads waiting to be.
 *
 * <p>A file is stored before its row is, and removed after its row is, through {@code media_file_deletion}, so a row never
 * names a file that's gone. A file can be left over if the server stops in between, which only wastes space.
 */
@Service
public class MediaAttachments {

    /**
     * As on Mastodon, which clients expect.
     */
    public static final int MAX_PER_STATUS = 4;

    public static final int MAX_DESCRIPTION_LENGTH = 1500;

    /**
     * The most pixels an uploaded image can have.
     */
    public static final long MAX_IMAGE_PIXELS = MediaFiles.MAX_IMAGE_PIXELS;

    /**
     * What can be uploaded.
     */
    public static final List<String> SUPPORTED_TYPES = MediaFiles.CONTENT_TYPES;

    private final MediaAttachmentRepository attachments;
    private final MediaFiles files;
    private final AttachmentProperties properties;

    MediaAttachments(MediaAttachmentRepository attachments, MediaFiles files, AttachmentProperties properties) {
        this.attachments = attachments;
        this.files = files;
        this.properties = properties;
    }

    public enum DeleteOutcome {DELETED, ATTACHED, NOT_FOUND}

    /**
     * Stores an upload, to be attached to a status before {@link AttachmentProperties#unattachedRetention} has passed.
     *
     * @throws InvalidMediaAttachmentException if the file isn't one that can be attached, or the account has too many
     *                                         uploads waiting
     */
    public MediaAttachment upload(UUID accountId, MediaUpload upload) {
        var description = description(upload.description());
        if (attachments.countAttachable(accountId) >= properties.maxUnattachedUploads()) {
            throw new InvalidMediaAttachmentException("You can't have more than %d uploads waiting to be posted"
                    .formatted(properties.maxUnattachedUploads()));
        }
        var limits = properties.limits();
        StoredMedia stored;
        try (var file = files.receive(upload.file(), limits.largest().toBytes());
             var thumbnail = upload.thumbnail() == null ? null : files.receive(upload.thumbnail(), limits.image().toBytes())) {
            stored = files.store(file, thumbnail, limits);
        } catch (InvalidMediaException e) {
            throw new InvalidMediaAttachmentException(e.getMessage());
        }
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var attachment = new MediaAttachment(
                Ids.timeOrdered(now), accountId, null, type(stored.kind()), stored.name(), stored.previewName(),
                stored.contentType(), stored.size(), stored.width(), stored.height(), stored.previewWidth(),
                stored.previewHeight(), description, upload.focus(), null, null, false, now);
        try {
            attachments.insert(attachment, now.plus(properties.unattachedRetention()));
        } catch (RuntimeException e) {
            files.delete(stored.name());
            files.delete(stored.previewName());
            throw e;
        }
        return attachment;
    }

    /**
     * Finds one of an account's uploads that hasn't been attached to a status, which is all Mastodon shows.
     */
    public Optional<MediaAttachment> findUnattached(UUID id, UUID accountId) {
        return attachments.findAttachable(id, accountId);
    }

    /**
     * Changes the description or focus of an upload that hasn't been attached yet. Once it is, it's changed by editing the
     * status, as on Mastodon.
     *
     * @param description the new one, or null to keep it. Blank removes it
     * @param focus       the new one, or null to keep it
     */
    public Optional<MediaAttachment> updateUnattached(UUID id, UUID accountId, String description, Focus focus) {
        return attachments.findAttachable(id, accountId).map(found -> {
            var newDescription = description == null ? found.description() : description(description);
            var newFocus = focus == null ? found.focus() : focus;
            attachments.updateDescription(id, newDescription, newFocus);
            return attachments.findAttachable(id, accountId).orElse(found);
        });
    }

    /**
     * Deletes an upload, which can only be done while it isn't attached to a status.
     */
    public DeleteOutcome deleteUnattached(UUID id, UUID accountId) {
        if (attachments.deleteUnattached(id, accountId)) {
            processDeletions();
            return DeleteOutcome.DELETED;
        }
        return attachments.findOwn(id, accountId).isPresent() ? DeleteOutcome.ATTACHED : DeleteOutcome.NOT_FOUND;
    }

    /**
     * @return each status's attachments, in order, for each status that has any
     */
    public Map<UUID, List<MediaAttachment>> findByStatuses(Collection<UUID> statusIds) {
        return attachments.findByStatuses(statusIds);
    }

    /**
     * Removes attachments whose statuses are gone, and uploads that weren't posted in time, and then their files. Safe to
     * stop partway, and to repeat.
     */
    public void removeOrphans() {
        while (attachments.removeOrphans() > 0) {
            // Each batch commits by itself.
        }
        processDeletions();
    }

    /**
     * Removes every attachment and upload of an account's, and their files, for when it's deleted.
     */
    public void removeAllBy(UUID accountId) {
        attachments.removeAllBy(accountId);
        processDeletions();
    }

    /**
     * Removes the files that nothing refers to any more. A file that can't be removed is only logged, and taken off the
     * list anyway, since it's only wasted space.
     */
    public void processDeletions() {
        List<String> names;
        do {
            names = attachments.findFilesToDelete();
            names.forEach(files::delete);
            attachments.forgetDeletedFiles(names);
        } while (!names.isEmpty());
    }

    /**
     * Checks a description, and makes a blank one none.
     *
     * @throws InvalidMediaAttachmentException if it's too long or can't be stored
     */
    static String description(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var description = value.strip();
        if (!StatusService.isStorable(description)) {
            throw new InvalidMediaAttachmentException("Description contains invalid characters");
        }
        if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidMediaAttachmentException(
                    "Description can't be longer than %d characters".formatted(MAX_DESCRIPTION_LENGTH));
        }
        return description;
    }

    private static AttachmentType type(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> AttachmentType.IMAGE;
            case VIDEO -> AttachmentType.VIDEO;
            case AUDIO -> AttachmentType.AUDIO;
        };
    }
}
