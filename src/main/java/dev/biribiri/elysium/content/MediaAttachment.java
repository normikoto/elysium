package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.media.Images;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A file attached to a status, or uploaded to be.
 *
 * @param statusId    null for one that isn't attached
 * @param fileName    the copy kept here, or null for a remote attachment that isn't
 * @param previewName a still image to show for it, or null for none
 * @param remoteUrl   where its author's server has it, or null for a local one
 * @param rejected    whether an admin's filter keeps it from being downloaded, so it's only linked to
 */
public record MediaAttachment(
        UUID id, UUID accountId, UUID statusId, AttachmentType type, String fileName, String previewName,
        String contentType, Long fileSize, Integer width, Integer height, Integer previewWidth, Integer previewHeight,
        String description, Focus focus, String blurhash, URI remoteUrl, boolean rejected, Instant createdAt) {

    /**
     * What clients are told it is. One that's rejected is only a link, whatever it was.
     */
    public AttachmentType shownType() {
        return rejected ? AttachmentType.UNKNOWN : type;
    }

    /**
     * @return the copy kept here, or else where its author's server has it
     */
    public URI url(InstanceProperties instance) {
        return local(fileName, instance).orElse(remoteUrl);
    }

    /**
     * @return the still kept here, or empty if there's none
     */
    public Optional<URI> previewUrl(InstanceProperties instance) {
        return local(previewName, instance);
    }

    public boolean isCached() {
        return fileName != null;
    }

    private static Optional<URI> local(String name, InstanceProperties instance) {
        return Optional.ofNullable(name).map(found -> instance.resolve(Images.pathOf(found)));
    }
}
