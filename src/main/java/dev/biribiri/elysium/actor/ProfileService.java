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
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Profiles of local accounts. Remote ones aren't stored yet, so they're always {@link Profile#EMPTY}.
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
                Collectors.mapping(row -> new ProfileField(row.name(), row.value(), row.verifiedAt()), Collectors.toList())));
        var found = new HashMap<UUID, Profile>();
        for (var row : profiles.find(actorIds)) {
            found.put(row.actorId(), new Profile(
                    row.displayName(),
                    row.note(),
                    image(row.avatar(), row.avatarStatic()),
                    image(row.header(), row.headerStatic()),
                    fields.getOrDefault(row.actorId(), List.of()),
                    row.updatedAt()));
        }
        return found;
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
                var newAvatar = choose(change.avatar(), avatar, old.avatar(), old.avatarStatic(), removed);
                var newHeader = choose(change.header(), header, old.header(), old.headerStatic(), removed);
                var row = new ProfileRepository.Row(
                        actorId,
                        displayName.orElse(old.displayName()),
                        note.orElse(old.note()),
                        newAvatar == null ? null : newAvatar.name(),
                        newAvatar == null ? null : newAvatar.staticName(),
                        newHeader == null ? null : newHeader.name(),
                        newHeader == null ? null : newHeader.staticName(),
                        old.updatedAt());
                var oldFields = profiles.findFields(List.of(actorId));
                var newFields = fields.map(list -> keepVerification(list, actorId, oldFields));
                // A form sends everything each time it's saved, and other servers shouldn't hear about nothing.
                if (row.equals(old) && newFields.map(oldFields::equals).orElse(true)) {
                    return removed;
                }
                var at = profiles.save(row);
                newFields.ifPresent(list -> profiles.replaceFields(actorId, list));
                events.publishEvent(new ProfileUpdated(actorId, at));
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
     * The field links that haven't been checked yet, for an active local account.
     */
    public List<FieldLink> linksToVerify(UUID actorId) {
        if (actors.findLocalById(actorId).isEmpty()) {
            return List.of();
        }
        var links = new ArrayList<FieldLink>();
        for (var row : profiles.findFields(List.of(actorId))) {
            var field = new ProfileField(row.name(), row.value(), row.verifiedAt());
            if (field.verifiedAt() == null) {
                field.link().ifPresent(link -> links.add(new FieldLink(row.position(), row.value(), link)));
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
        var removed = transaction.execute(status -> profiles.delete(actorId));
        if (removed != null) {
            removed.ifPresent(row -> {
                if (row.avatar() != null) {
                    images.delete(new StoredImage(row.avatar(), row.avatarStatic()));
                }
                if (row.header() != null) {
                    images.delete(new StoredImage(row.header(), row.headerStatic()));
                }
            });
        }
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

    private StoredImage store(ProfileChange.Image change, ImageKind kind, List<StoredImage> stored) {
        if (!(change instanceof ProfileChange.Image.Upload(var file))) {
            return null;
        }
        try {
            var image = images.store(file, kind, properties.maxImageSize());
            stored.add(image);
            return image;
        } catch (InvalidImageException e) {
            var name = kind == ImageKind.AVATAR ? "Avatar" : "Header";
            throw new InvalidProfileException("Validation failed: " + name + ": " + e.getMessage(), e);
        }
    }

    /**
     * @param removed gets the old image, if it's being replaced or removed
     * @return the image the profile should have, or null for none
     */
    private static StoredImage choose(
            ProfileChange.Image change, StoredImage uploaded, String oldName, String oldStatic, List<StoredImage> removed) {
        var old = oldName == null ? null : new StoredImage(oldName, oldStatic);
        if (change instanceof ProfileChange.Image.Keep) {
            return old;
        }
        if (old != null) {
            removed.add(old);
        }
        return uploaded;
    }

    private static ProfileImage image(String name, String staticName) {
        return name == null ? null : new ProfileImage(Images.pathOf(name), Images.pathOf(staticName));
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
