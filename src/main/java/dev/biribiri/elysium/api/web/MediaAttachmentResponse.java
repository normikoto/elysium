package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.AttachmentType;
import dev.biribiri.elysium.content.MediaAttachment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * Mastodon's MediaAttachment entity.
 */
@Schema(name = "MediaAttachment", description = """
        Mastodon's MediaAttachment entity. Images use WebP, and animated GIF uploads become animated WebP with \
        type `image`. Video and audio preserve the original files. They have no `meta.original` and have a \
        `preview_url` only if the upload included a thumbnail.

        `blurhash` is null for local uploads. Cacheable remote attachments that have no local copy use `/media_proxy/` \
        for `url` and image `preview_url`, which fetches the file and redirects to the local copy. If Elysium cannot \
        cache an attachment, its `url` is its `remote_url`. If an admin rejects media from the server or account, the \
        attachment has type `unknown`.""")
record MediaAttachmentResponse(
        String id,
        @Schema(allowableValues = {"image", "video", "audio", "unknown"}) String type,
        String url,
        @JsonProperty("preview_url") String previewUrl,
        @JsonProperty("remote_url") String remoteUrl,
        @JsonProperty("preview_remote_url") String previewRemoteUrl,
        @JsonProperty("text_url") String textUrl,
        Meta meta,
        String description,
        String blurhash
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Meta(Size original, Size small, Focus focus) {
    }

    record Size(int width, int height, String size, double aspect) {

        static Size of(Integer width, Integer height) {
            if (width == null || height == null || width < 1 || height < 1) {
                return null;
            }
            return new Size(width, height, width + "x" + height, (double) width / height);
        }
    }

    record Focus(double x, double y) {
    }

    static MediaAttachmentResponse from(MediaAttachment attachment, InstanceProperties instance) {
        var type = attachment.shownType();
        var url = attachment.rejected() ? attachment.remoteUrl() : attachment.url(instance);
        // A remote image that isn't kept here is its own preview, as clients expect one for every image.
        var preview = attachment.rejected() ? null : attachment.previewUrl(instance)
                .or(() -> Optional.ofNullable(!attachment.isCached() && type == AttachmentType.IMAGE
                        ? attachment.remoteUrl() : null))
                .orElse(null);
        var focus = attachment.focus() == null ? null : new Focus(attachment.focus().x(), attachment.focus().y());
        return new MediaAttachmentResponse(
                attachment.id().toString(),
                type.name().toLowerCase(Locale.ROOT),
                string(url),
                string(preview),
                string(attachment.remoteUrl()),
                null,
                null,
                new Meta(Size.of(attachment.width(), attachment.height()),
                        Size.of(attachment.previewWidth(), attachment.previewHeight()), focus),
                attachment.description(),
                attachment.blurhash());
    }

    private static String string(URI uri) {
        return uri == null ? null : uri.toString();
    }
}
