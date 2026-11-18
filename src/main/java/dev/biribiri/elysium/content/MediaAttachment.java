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
 * @param givenUp     whether it's a remote attachment that couldn't be kept here, such as one that's too large, or that
 *                    isn't kept because copies are turned off, so it's only linked to
 */
public record MediaAttachment(
        UUID id, UUID accountId, UUID statusId, AttachmentType type, String fileName, String previewName,
        String contentType, Long fileSize, Integer width, Integer height, Integer previewWidth, Integer previewHeight,
        String description, Focus focus, String blurhash, URI remoteUrl, boolean rejected, boolean givenUp,
        Instant createdAt) {

    /**
     * Where a remote attachment that isn't kept here, but can be, is served from. A request for it downloads it, so that
     * browsers don't fetch it from its server, even after its copy has been removed to save space.
     */
    public static final String PROXY_PATH = "/media_proxy/";

    public static final String PROXY_ORIGINAL = "original";

    public static final String PROXY_SMALL = "small";

    /**
     * What clients are told it is. One that's rejected is only a link, whatever it was.
     */
    public AttachmentType shownType() {
        return rejected ? AttachmentType.UNKNOWN : type;
    }

    /**
     * @return the copy kept here, or else the {@linkplain #isProxied proxy}, or else where its author's server has it
     */
    public URI url(InstanceProperties instance) {
        return local(fileName, instance).or(() -> proxied(PROXY_ORIGINAL, instance)).orElse(remoteUrl);
    }

    /**
     * @return the still kept here, or the proxy's for an image, or empty if there's none
     */
    public Optional<URI> previewUrl(InstanceProperties instance) {
        return local(previewName, instance)
                .or(() -> type == AttachmentType.IMAGE ? proxied(PROXY_SMALL, instance) : Optional.empty());
    }

    /**
     * @param preview whether to give the still, which is the file itself for one that has none
     * @return the copy kept here, or empty if there's none
     */
    public Optional<URI> keptUrl(InstanceProperties instance, boolean preview) {
        return local(preview && previewName != null ? previewName : fileName, instance);
    }

    public boolean isCached() {
        return fileName != null;
    }

    /**
     * Whether it's served through {@link #PROXY_PATH}: a remote attachment with no copy here that can have one.
     */
    public boolean isProxied() {
        return fileName == null && remoteUrl != null && !rejected && !givenUp;
    }

    public static String proxyPathOf(UUID id, String size) {
        return PROXY_PATH + id + "/" + size;
    }

    private Optional<URI> proxied(String size, InstanceProperties instance) {
        return isProxied() ? Optional.of(instance.resolve(proxyPathOf(id, size))) : Optional.empty();
    }

    private static Optional<URI> local(String name, InstanceProperties instance) {
        return Optional.ofNullable(name).map(found -> instance.resolve(Images.pathOf(found)));
    }
}
