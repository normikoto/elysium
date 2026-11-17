package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.actor.internal.ActorRepository;
import dev.biribiri.elysium.actor.internal.ProfileRepository;
import dev.biribiri.elysium.media.ImageKind;
import dev.biribiri.elysium.media.Images;
import dev.biribiri.elysium.media.InvalidImageException;
import dev.biribiri.elysium.media.StoredImage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Profiles of local accounts, which their owners edit, and of accounts on other servers, which are copied from their actor
 * documents whenever those are fetched.
 */
@Service
public class ProfileService {

    /**
     * Mastodon's limits, which clients enforce as people type.
     */
    public static final int MAX_DISPLAY_NAME = 30;
    public static final int MAX_NOTE = 500;
    public static final int MAX_FIELD_NAME = 255;
    public static final int MAX_FIELD_VALUE = 255;

    private final ProfileRepository profiles;
    private final ActorRepository actors;
    private final Images images;
    private final ProfileProperties properties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    private static final int REMOVAL_BATCH = 500;

    ProfileService(
            ProfileRepository profiles,
            ActorRepository actors,
            Images images,
            ProfileProperties properties,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager) {
        this.profiles = profiles;
        this.actors = actors;
        this.images = images;
        this.properties = properties;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public int maxFields() {
        return properties.maxFields();
    }

    public Profile find(UUID actorId) {
        return findAll(List.of(actorId)).getOrDefault(actorId, Profile.EMPTY);
    }

    /**
     * @return the profiles that exist, by actor. An account without one in here has {@link Profile#EMPTY}.
     */
    public Map<UUID, Profile> findAll(Collection<UUID> actorIds) {
        var fields = profiles.findFields(actorIds).stream().collect(Collectors.groupingBy(
                ProfileRepository.FieldRow::actorId,
                Collectors.mapping(ProfileService::field, Collectors.toList())));
        var found = new HashMap<UUID, Profile>();
        for (var row : profiles.find(actorIds)) {
            var remote = row.remote();
            found.put(row.actorId(), new Profile(
                    row.displayName(),
                    row.note(),
                    image(row.avatar(), remote == null ? null : remote.avatarUrl()),
                    image(row.header(), remote == null ? null : remote.headerUrl()),
                    fields.getOrDefault(row.actorId(), List.of()),
                    row.manuallyApprovesFollowers(),
                    row.indexable(),
                    row.updatedAt(),
                    remote == null ? null : new Profile.Remote(
                            remote.noteHtml(), remote.url(), remote.bot(), remote.group(), remote.published()),
                    row.alsoKnownAs(),
                    row.movedTo()));
        }
        return found;
    }

    /**
     * Replaces what's stored about an account on another server's profile with what its actor document says now. An image
     * whose URL is unchanged keeps its copy, and one that changed loses it once this commits, and is downloaded again
     * through {@link RemoteProfileImageAdded}. Nothing is sent anywhere, as {@link ProfileUpdated} would be.
     *
     * <p>The caller checks that the actor is active and on another server, in the same transaction.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there's no transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceRemote(UUID actorId, RemoteProfile profile) {
        var old = profiles.lock(actorId);
        var oldRemote = old.remote();
        var removed = new ArrayList<StoredImage>();
        var avatar = keptImage(old.avatar(), oldRemote == null ? null : oldRemote.avatarUrl(), profile.avatarUrl(), removed);
        var header = keptImage(old.header(), oldRemote == null ? null : oldRemote.headerUrl(), profile.headerUrl(), removed);
        var row = new ProfileRepository.Row(
                actorId,
                profile.displayName(),
                profile.note(),
                avatar,
                header,
                profile.manuallyApprovesFollowers(),
                profile.indexable(),
                old.updatedAt(),
                new ProfileRepository.Remote(
                        profile.noteHtml(),
                        profile.avatarUrl(),
                        givenUp(oldRemote, false, profile.avatarUrl()),
                        profile.headerUrl(),
                        givenUp(oldRemote, true, profile.headerUrl()),
                        profile.url(),
                        profile.bot(),
                        profile.group(),
                        profile.published()),
                profile.alsoKnownAs(),
                profile.movedTo());
        if (!row.equals(old)) {
            profiles.saveRemote(row);
        }
        var fields = new ArrayList<ProfileRepository.FieldRow>();
        for (var i = 0; i < profile.fields().size(); i++) {
            var field = profile.fields().get(i);
            fields.add(new ProfileRepository.FieldRow(actorId, i, field.name(), field.value(), null, field.valueHtml()));
        }
        if (!fields.equals(profiles.findFields(List.of(actorId)))) {
            profiles.replaceFields(actorId, fields);
        }
        if (!removed.isEmpty()) {
            afterCommit(() -> removed.forEach(images::delete));
        }
        var downloadAvatar = needsDownload(row.avatar(), row.remote().avatarUrl(), row.remote().avatarGivenUp());
        var downloadHeader = needsDownload(row.header(), row.remote().headerUrl(), row.remote().headerGivenUp());
        // Published each time until it's kept, so that an image whose server was over its budget is tried again.
        if (downloadAvatar || downloadHeader) {
            events.publishEvent(new RemoteProfileImageAdded(actorId, downloadAvatar, downloadHeader));
        }
    }

    /**
     * @param header whether it's the header rather than the avatar
     * @return where to download an account on another server's image from, if it isn't kept here and should be
     */
    public Optional<URI> findImageToDownload(UUID actorId, boolean header) {
        return profiles.findImageToDownload(actorId, header);
    }

    /**
     * Keeps a downloaded avatar or header of an account on another server, re-encoded as an upload would be.
     *
     * @param url where it was downloaded from, so that an image the profile no longer has isn't kept
     * @return whether it's kept. One that isn't an image that can be used, or is too large, stays a link
     */
    public boolean cacheRemoteImage(UUID actorId, boolean header, URI url, byte[] image) {
        StoredImage stored;
        try {
            stored = images.store(image, header ? ImageKind.HEADER : ImageKind.AVATAR, properties.maxImageSize());
        } catch (InvalidImageException e) {
            profiles.giveUpCaching(actorId, header, url);
            return false;
        }
        boolean kept;
        try {
            kept = profiles.cacheImage(actorId, header, url, stored);
        } catch (RuntimeException e) {
            images.delete(stored);
            throw e;
        }
        if (!kept) {
            images.delete(stored);
        }
        return kept;
    }

    /**
     * Links to an image of an account on another server from then on, until its URL changes.
     */
    public void giveUpCaching(UUID actorId, boolean header, URI url) {
        profiles.giveUpCaching(actorId, header, url);
    }

    /**
     * The largest avatar or header that's kept, which a download can stop at.
     */
    public DataSize maxImageSize() {
        return properties.maxImageSize();
    }

    /**
     * Removes the copies of an account on another server's images, which are linked to from then on, for when its media
     * is rejected. Safe to repeat.
     */
    public void uncacheRemoteImages(UUID actorId) {
        var uncached = transaction.execute(status -> profiles.uncacheImages(actorId));
        uncached.images().forEach(images::delete);
    }

    /**
     * Like {@link #uncacheRemoteImages(UUID)}, for all of a server's accounts. Safe to stop partway, and to repeat.
     *
     * @param domain as actors have it
     */
    public void uncacheRemoteImagesForDomain(String domain) {
        ProfileRepository.Uncached batch;
        do {
            batch = transaction.execute(status -> profiles.uncacheImagesForDomain(domain, REMOVAL_BATCH));
            batch.images().forEach(images::delete);
        } while (batch.profiles() > 0);
    }

    /**
     * Lets an account on another server's images be downloaded again, the next time it's fetched, for when its media stops
     * being rejected.
     */
    public void allowRemoteImages(UUID actorId) {
        profiles.allowImages(actorId);
    }

    /**
     * Like {@link #allowRemoteImages(UUID)}, for all of a server's accounts.
     *
     * @param domain as actors have it
     */
    public void allowRemoteImagesForDomain(String domain) {
        while (profiles.allowImagesForDomain(domain, REMOVAL_BATCH) > 0) {
            // Each batch commits by itself.
        }
    }

    /**
     * Removes the profiles of a server's accounts and their images, for when it's blocked. Safe to stop partway, and to
     * repeat.
     *
     * @param domain as actors have it
     */
    public void removeForDomain(String domain) {
        List<ProfileRepository.Row> batch;
        do {
            batch = profiles.deleteBatchForDomain(domain, REMOVAL_BATCH);
            batch.forEach(this::deleteImages);
        } while (!batch.isEmpty());
    }

    /**
     * Whether requests to follow a local account wait for its approval. This holds the setting until the caller's
     * transaction ends, so that a request can't be left waiting by an account that has just stopped approving them:
     * {@link FollowerApprovalTurnedOff} is only published once the change commits, and it can't commit before this.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there's no transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockFollowerApproval(UUID actorId) {
        return profiles.lockFollowerApproval(actorId);
    }

    /**
     * Changes a local account's profile. New images are stored before the change is saved, and replaced ones are deleted
     * once it is. Followers' servers are told through {@link ProfileUpdated}.
     *
     * @return false if there's no active local account to change
     * @throws InvalidProfileException if the change breaks a limit or an image can't be used, and then nothing is changed
     */
    public boolean update(UUID actorId, ProfileChange change) {
        var displayName = change.displayName().map(name -> singleLine(name, "Display name", MAX_DISPLAY_NAME));
        var note = change.note().map(text -> text(text.strip(), "Bio", MAX_NOTE));
        var fields = change.fields().map(this::checkFields);
        if (actors.findLocalById(actorId).isEmpty()) {
            return false;
        }
        if (change.isEmpty()) {
            return true;
        }
        var stored = new ArrayList<StoredImage>();
        try {
            var avatar = store(change.avatar(), ImageKind.AVATAR, stored);
            var header = store(change.header(), ImageKind.HEADER, stored);
            var replaced = transaction.execute(status -> {
                if (actors.findLocalById(actorId).isEmpty()) {
                    return null;
                }
                var old = profiles.lock(actorId);
                var removed = new ArrayList<StoredImage>();
                var row = new ProfileRepository.Row(
                        actorId,
                        displayName.orElse(old.displayName()),
                        note.orElse(old.note()),
                        choose(change.avatar(), avatar, old.avatar(), removed),
                        choose(change.header(), header, old.header(), removed),
                        change.manuallyApprovesFollowers().orElse(old.manuallyApprovesFollowers()),
                        change.indexable().orElse(old.indexable()),
                        old.updatedAt(),
                        null,
                        old.alsoKnownAs(),
                        old.movedTo());
                var oldFields = fields.isPresent() ? profiles.findFields(List.of(actorId)) : List.<ProfileRepository.FieldRow>of();
                var newFields = fields.map(list -> keepVerification(list, actorId, oldFields));
                // A form sends everything each time it's saved, and other servers shouldn't hear about nothing.
                if (row.equals(old) && newFields.map(oldFields::equals).orElse(true)) {
                    return removed;
                }
                var at = profiles.save(row);
                newFields.ifPresent(list -> profiles.replaceFields(actorId, list));
                events.publishEvent(new ProfileUpdated(actorId, at));
                if (old.manuallyApprovesFollowers() && !row.manuallyApprovesFollowers()) {
                    events.publishEvent(new FollowerApprovalTurnedOff(actorId));
                }
                return removed;
            });
            if (replaced == null) {
                stored.forEach(images::delete);
                return false;
            }
            replaced.forEach(images::delete);
            return true;
        } catch (RuntimeException e) {
            stored.forEach(images::delete);
            throw e;
        }
    }

    /**
     * The field links that haven't been checked yet. The caller checks that the account is active, as it needs the actor.
     */
    public List<FieldLink> linksToVerify(UUID actorId) {
        var links = new ArrayList<FieldLink>();
        for (var row : profiles.findFields(List.of(actorId))) {
            if (row.verifiedAt() == null) {
                field(row).link().ifPresent(link -> links.add(new FieldLink(row.position(), row.value(), link)));
            }
        }
        return links;
    }

    /**
     * A field whose value is a link.
     *
     * @param position which field it is, from 0
     * @param value    the value as it was when this was read, which has to be unchanged for it to be marked verified
     */
    public record FieldLink(int position, String value, URI uri) {
    }

    /**
     * Marks a field as linked back to from the page its value links to.
     *
     * @return false if the field has changed since, or was already verified
     */
    public boolean markVerified(UUID actorId, FieldLink link, Instant at) {
        return profiles.markVerified(actorId, link.position(), link.value(), at);
    }

    /**
     * Removes a profile and its images, for an account that's deleted. This is safe to repeat.
     */
    public void removeAll(UUID actorId) {
        profiles.delete(actorId).ifPresent(this::deleteImages);
    }

    private void deleteImages(ProfileRepository.Row row) {
        if (row.avatar() != null) {
            images.delete(row.avatar());
        }
        if (row.header() != null) {
            images.delete(row.header());
        }
    }

    /**
     * @param removed gets the old copy, if the image's URL changed
     * @return the copy that's still the image at {@code newUrl}, or null
     */
    private static StoredImage keptImage(StoredImage old, URI oldUrl, URI newUrl, List<StoredImage> removed) {
        if (old == null || Objects.equals(oldUrl, newUrl)) {
            return old;
        }
        removed.add(old);
        return null;
    }

    /**
     * Giving up lasts until the image's URL changes, which is a new image.
     */
    private static boolean givenUp(ProfileRepository.Remote old, boolean header, URI newUrl) {
        if (old == null) {
            return false;
        }
        return header
                ? old.headerGivenUp() && Objects.equals(old.headerUrl(), newUrl)
                : old.avatarGivenUp() && Objects.equals(old.avatarUrl(), newUrl);
    }

    private static boolean needsDownload(StoredImage copy, URI url, boolean givenUp) {
        return copy == null && url != null && !givenUp;
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private List<ProfileChange.NewField> checkFields(List<ProfileChange.NewField> fields) {
        var kept = fields.stream()
                .map(field -> new ProfileChange.NewField(
                        singleLine(field.name(), "Field name", MAX_FIELD_NAME),
                        singleLine(field.value(), "Field value", MAX_FIELD_VALUE)))
                // A row left blank on a form is how a field is removed.
                .filter(field -> !field.name().isEmpty() || !field.value().isEmpty())
                .toList();
        if (kept.size() > properties.maxFields()) {
            throw new InvalidProfileException(
                    "Validation failed: Fields can't be more than " + properties.maxFields());
        }
        return kept;
    }

    /**
     * A field keeps its verification if its value hasn't changed, wherever it's moved to, so that reordering fields
     * doesn't mean checking them all again.
     */
    private static List<ProfileRepository.FieldRow> keepVerification(
            List<ProfileChange.NewField> fields, UUID actorId, List<ProfileRepository.FieldRow> oldFields) {
        var verified = new HashMap<String, Instant>();
        for (var old : oldFields) {
            if (old.verifiedAt() != null) {
                verified.put(old.value(), old.verifiedAt());
            }
        }
        var rows = new ArrayList<ProfileRepository.FieldRow>();
        for (var i = 0; i < fields.size(); i++) {
            var field = fields.get(i);
            rows.add(new ProfileRepository.FieldRow(actorId, i, field.name(), field.value(), verified.get(field.value())));
        }
        return rows;
    }

    /**
     * Refuses an image that's too large before it's read, since the server takes larger uploads for statuses.
     *
     * @throws InvalidProfileException if it's larger than {@link ProfileProperties#maxImageSize}
     */
    public void checkAvatarSize(long size) {
        checkImageSize(ImageKind.AVATAR, size);
    }

    /**
     * Like {@link #checkAvatarSize}.
     */
    public void checkHeaderSize(long size) {
        checkImageSize(ImageKind.HEADER, size);
    }

    private void checkImageSize(ImageKind kind, long size) {
        try {
            Images.checkSize(size, properties.maxImageSize());
        } catch (InvalidImageException e) {
            throw invalid(kind, e);
        }
    }

    private static InvalidProfileException invalid(ImageKind kind, InvalidImageException e) {
        var name = kind == ImageKind.AVATAR ? "Avatar" : "Header";
        return new InvalidProfileException("Validation failed: " + name + ": " + e.getMessage(), e);
    }

    private StoredImage store(ProfileChange.Image change, ImageKind kind, List<StoredImage> stored) {
        if (!(change instanceof ProfileChange.Image.Upload(var file))) {
            return null;
        }
        try {
            var image = images.store(file, kind, properties.maxImageSize());
            stored.add(image);
            return image;
        } catch (InvalidImageException e) {
            throw invalid(kind, e);
        }
    }

    /**
     * @param removed gets the old image, if it's being replaced or removed
     * @return the image the profile should have, or null for none
     */
    private static StoredImage choose(
            ProfileChange.Image change, StoredImage uploaded, StoredImage old, List<StoredImage> removed) {
        if (change instanceof ProfileChange.Image.Keep) {
            return old;
        }
        if (old != null) {
            removed.add(old);
        }
        return uploaded;
    }

    /**
     * @param remoteUrl where an account on another server has the image, or null
     */
    private static ProfileImage image(StoredImage image, URI remoteUrl) {
        if (image == null) {
            return remoteUrl == null ? null : new ProfileImage(null, null, remoteUrl);
        }
        return new ProfileImage(Images.pathOf(image.name()), Images.pathOf(image.staticName()), remoteUrl);
    }

    private static ProfileField field(ProfileRepository.FieldRow row) {
        return new ProfileField(row.name(), row.value(), row.verifiedAt(), row.valueHtml());
    }

    /**
     * Names and field values are shown on one line, so line breaks become spaces.
     */
    private static String singleLine(String value, String field, int max) {
        return text(value.replaceAll("[\\r\\n]+", " ").strip(), field, max);
    }

    private static String text(String value, String field, int max) {
        if (value.indexOf('\0') >= 0) {
            throw new InvalidProfileException("Validation failed: " + field + " can't contain NUL characters");
        }
        if (value.codePointCount(0, value.length()) > max) {
            throw new InvalidProfileException(
                    "Validation failed: " + field + " is too long (maximum is " + max + " characters)");
        }
        return value;
    }
}
