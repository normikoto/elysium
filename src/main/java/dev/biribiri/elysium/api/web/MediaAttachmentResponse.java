package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.MediaAttachment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.util.Locale;

/**
 * Mastodon's MediaAttachment entity.
 */
@Schema(name = "MediaAttachment", description = """
        Mastodon's MediaAttachment entity. An image is served as WebP, and an animated GIF as an animated WebP with type \
        `image` rather than `gifv`. Video and audio are served as they were uploaded, and have no `meta.original`, since \
        they aren't decoded, and no `preview_url` unless a thumbnail was uploaded with them. `blurhash` is null for \
        uploads here. A remote attachment that isn't kept here has its `remote_url` as its `url`, and one from a server or \
        account whose media is rejected has type `unknown`.""")
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
        var preview = attachment.rejected() ? null : attachment.previewUrl(instance).orElse(null);
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
