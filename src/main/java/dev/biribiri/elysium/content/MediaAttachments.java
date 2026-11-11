package dev.biribiri.elysium.content;

import dev.biribiri.elysium.content.internal.MediaAttachmentRepository;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.media.InvalidMediaException;
import dev.biribiri.elysium.media.MediaFiles;
import dev.biribiri.elysium.media.MediaKind;
import dev.biribiri.elysium.media.StoredMedia;
import dev.biribiri.elysium.media.UnsupportedMediaException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
    private final ApplicationEventPublisher events;

    MediaAttachments(MediaAttachmentRepository attachments, MediaFiles files, AttachmentProperties properties,
                     ApplicationEventPublisher events) {
        this.events = events;
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
     * Adds the attachments of a status from another server, which are only links to its server's copies until they're
     * downloaded. Has to run in the transaction that stores the status.
     */
    void addRemote(UUID statusId, UUID authorId, List<RemoteAttachment> remote) {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var added = new ArrayList<UUID>();
        for (var i = 0; i < remote.size(); i++) {
            var attachment = fromRemote(remote.get(i), authorId, statusId, now);
            attachments.insert(attachment, null, i);
            if (!attachment.rejected()) {
                added.add(attachment.id());
            }
        }
        published(added);
    }

    private void published(List<UUID> added) {
        if (!added.isEmpty()) {
            events.publishEvent(new RemoteMediaAdded(added));
        }
    }

    /**
     * Replaces the attachments of a status from another server with those an edit gives. One that's still there, by its
     * URL, keeps its ID and any copy of it, and the others are removed. Has to run in the transaction that edits the
     * status.
     *
     * @return whether any were removed, whose files {@link #processDeletions} then removes
     */
    boolean replaceRemote(UUID statusId, UUID authorId, List<RemoteAttachment> remote) {
        var all = attachments.findByStatusAndLock(statusId);
        var current = new HashMap<URI, MediaAttachment>();
        for (var attachment : all) {
            if (attachment.remoteUrl() != null) {
                current.putIfAbsent(attachment.remoteUrl(), attachment);
            }
        }
        var kept = new ArrayList<UUID>();
        var added = new ArrayList<UUID>();
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (var i = 0; i < remote.size(); i++) {
            var given = fromRemote(remote.get(i), authorId, statusId, now);
            var existing = current.remove(given.remoteUrl());
            if (existing == null) {
                attachments.insert(given, null, i);
                kept.add(given.id());
                if (!given.rejected()) {
                    added.add(given.id());
                }
            } else {
                attachments.updateRemote(existing.id(), i, given.description(), given.focus(), given.blurhash(),
                        given.width(), given.height());
                kept.add(existing.id());
            }
        }
        attachments.removeFromStatusExcept(statusId, kept);
        published(added);
        return all.stream().anyMatch(attachment -> !kept.contains(attachment.id()));
    }

    /**
     * Another server's description is plain text, as on Mastodon, so it's only made storable and cut to length.
     */
    private static MediaAttachment fromRemote(RemoteAttachment remote, UUID authorId, UUID statusId, Instant now) {
        return new MediaAttachment(
                Ids.timeOrdered(now), authorId, statusId, remote.type(), null, null, remote.mediaType(), null,
                remote.width(), remote.height(), null, null, remoteDescription(remote.description()), remote.focus(),
                remote.blurhash(), remote.url(), remote.rejected(), now);
    }

    static String remoteDescription(String value) {
        if (value == null) {
            return null;
        }
        var description = new StringBuilder();
        value.strip().codePoints()
                .filter(codePoint -> codePoint != 0 && !(codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE))
                .limit(MAX_DESCRIPTION_LENGTH)
                .forEach(description::appendCodePoint);
        return description.isEmpty() ? null : description.toString();
    }

    public enum CacheOutcome {
        /** The copy is kept, and served from here. */
        CACHED,
        /** It isn't a file that's accepted, or is too large, so it stays a link. */
        REFUSED,
        /** It was removed, rejected, or downloaded meanwhile, so the copy was discarded. */
        DISCARDED
    }

    /**
     * Finds another server's attachment that should be downloaded, which is one that hasn't been tried, isn't rejected,
     * and still has its status.
     */
    public Optional<RemoteMediaSource> findForDownload(UUID id) {
        return attachments.findForDownload(id);
    }

    /**
     * Receives a download, stopping at the largest size any attachment can be.
     *
     * @throws InvalidMediaAttachmentException if it's larger than that
     * @throws IOException                     if reading it fails, as the stream it reads from says, so that a caller can
     *                                         tell a body that's too large from a connection that broke
     */
    public ReceivedMedia receive(InputStream body) throws IOException {
        try {
            return new ReceivedMedia(files.receive(body, properties.largest().toBytes()));
        } catch (InvalidMediaException e) {
            throw new InvalidMediaAttachmentException(e.getMessage());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Keeps a downloaded copy of another server's attachment, checked and re-encoded as an upload would be. What it is
     * comes from the file, whatever its server said.
     */
    public CacheOutcome cacheRemote(UUID id, ReceivedMedia file) {
        StoredMedia stored;
        try {
            stored = files.store(file.file, null, properties.limits());
        } catch (UnsupportedMediaException e) {
            // Whatever its server said it was, it's only shown as a link.
            attachments.giveUpCaching(id, AttachmentType.UNKNOWN);
            return CacheOutcome.REFUSED;
        } catch (InvalidMediaException e) {
            attachments.giveUpCaching(id, null);
            return CacheOutcome.REFUSED;
        }
        boolean recorded;
        try {
            recorded = attachments.cache(id, type(stored.kind()), stored.name(), stored.previewName(),
                    stored.contentType(), stored.size(), stored.width(), stored.height(), stored.previewWidth(),
                    stored.previewHeight());
        } catch (RuntimeException e) {
            recorded = false;
            discard(stored);
            throw e;
        }
        if (!recorded) {
            discard(stored);
            return CacheOutcome.DISCARDED;
        }
        return CacheOutcome.CACHED;
    }

    /**
     * Stops another server's attachment from being downloaded again, such as when its server says it's gone.
     */
    public void giveUpCaching(UUID id) {
        attachments.giveUpCaching(id, null);
    }

    /**
     * Removes the copies of other servers' attachments that are older than {@link AttachmentProperties#remoteCacheRetention},
     * which are linked again afterward. Safe to stop partway, and to repeat.
     */
    public void pruneRemoteCopies() {
        var before = Instant.now().minus(properties.remoteCacheRetention());
        while (attachments.uncacheBefore(before) > 0) {
            // Each batch commits by itself.
        }
        processDeletions();
    }

    /**
     * Rejects the attachments of a server's actors, and removes the copies of them, for when an admin starts rejecting
     * its media. They're shown as links from then on. Safe to repeat, and to stop partway.
     *
     * @param domain a domain as domain blocks have it, which covers its subdomains
     */
    public void rejectForDomain(String domain) {
        while (attachments.rejectForDomain(domain) > 0) {
            // Each batch commits by itself.
        }
        processDeletions();
    }

    /**
     * Like {@link #rejectForDomain}, for one account on another server.
     */
    public void rejectForAccount(UUID actorId) {
        while (attachments.rejectForAccount(actorId) > 0) {
            // Each batch commits by itself.
        }
        processDeletions();
    }

    /**
     * Stops rejecting a server's attachments, apart from those of accounts whose own are still rejected, or that another
     * block still covers. They're shown as what they are again, but as links, since copies aren't downloaded again.
     */
    public void allowForDomain(String domain) {
        while (attachments.allowForDomain(domain) > 0) {
            // Each batch commits by itself.
        }
    }

    /**
     * Like {@link #allowForDomain}, for one account, unless a block on its server still rejects its media.
     */
    public void allowForAccount(UUID actorId) {
        while (attachments.allowForAccount(actorId) > 0) {
            // Each batch commits by itself.
        }
    }

    private void discard(StoredMedia stored) {
        files.delete(stored.name());
        files.delete(stored.previewName());
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
