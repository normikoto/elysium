package dev.biribiri.elysium.actor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Changes to a profile. Whatever's empty or {@link Image#KEEP} stays as it is.
 *
 * @param fields all the fields the profile should have, in order, replacing the ones it has
 */
public record ProfileChange(
        Optional<String> displayName,
        Optional<String> note,
        Optional<List<NewField>> fields,
        Image avatar,
        Image header) {

    public static final ProfileChange NONE =
            new ProfileChange(Optional.empty(), Optional.empty(), Optional.empty(), Image.KEEP, Image.KEEP);

    public ProfileChange {
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(note);
        fields = fields.map(List::copyOf);
        Objects.requireNonNull(avatar);
        Objects.requireNonNull(header);
    }

    public record NewField(String name, String value) {
        public NewField {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
        }

        /**
         * Pairs names and values sent as two lists, as forms send them. The shorter list is padded with empty strings.
         */
        public static List<NewField> zip(List<String> names, List<String> values) {
            var fields = new ArrayList<NewField>();
            for (var i = 0; i < Math.max(names.size(), values.size()); i++) {
                fields.add(new NewField(i < names.size() ? names.get(i) : "", i < values.size() ? values.get(i) : ""));
            }
            return fields;
        }
    }

    /**
     * What to do with an image.
     */
    public sealed interface Image {
        Image KEEP = new Keep();
        Image REMOVE = new Remove();

        static Image upload(byte[] file) {
            return new Upload(file);
        }

        record Keep() implements Image {
        }

        record Remove() implements Image {
        }

        record Upload(byte[] file) implements Image {
            public Upload {
                Objects.requireNonNull(file);
            }
        }
    }

    public ProfileChange withDisplayName(String value) {
        return new ProfileChange(Optional.of(value), note, fields, avatar, header);
    }

    public ProfileChange withNote(String value) {
        return new ProfileChange(displayName, Optional.of(value), fields, avatar, header);
    }

    public ProfileChange withFields(List<NewField> value) {
        return new ProfileChange(displayName, note, Optional.of(value), avatar, header);
    }

    public ProfileChange withAvatar(Image value) {
        return new ProfileChange(displayName, note, fields, value, header);
    }

    public ProfileChange withHeader(Image value) {
        return new ProfileChange(displayName, note, fields, avatar, value);
    }

    public boolean isEmpty() {
        return equals(NONE);
    }
}
