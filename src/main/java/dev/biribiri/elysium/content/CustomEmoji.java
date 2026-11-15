package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.media.Images;

import java.net.URI;
import java.util.UUID;

/**
 * A custom emoji, this server's own or another's.
 *
 * @param shortcode  its name, without colons
 * @param domain     the domain of the server it's from, as actors have it, or null for this server's own
 * @param uri        its ID, which another server gives, and which is {@code /emojis/{id}} here for this server's own
 * @param imageName  its image in the media directory, or null for another server's that hasn't been downloaded
 * @param staticName its first frame, which is {@code imageName} for a still image
 * @param remoteUrl  where its server has the image, for another server's
 * @param category   what the picker groups it under, or null
 */
public record CustomEmoji(
        UUID id,
        String shortcode,
        String domain,
        URI uri,
        String imageName,
        String staticName,
        URI remoteUrl,
        String category,
        boolean visibleInPicker) {

    public boolean isLocal() {
        return domain == null;
    }

    /**
     * Another server's emoji that hasn't been downloaded is shown from its server, as an attachment is.
     */
    public URI url(InstanceProperties instance) {
        return imageName == null ? remoteUrl : instance.resolve(Images.pathOf(imageName));
    }

    public URI staticUrl(InstanceProperties instance) {
        return staticName == null ? remoteUrl : instance.resolve(Images.pathOf(staticName));
    }
}
