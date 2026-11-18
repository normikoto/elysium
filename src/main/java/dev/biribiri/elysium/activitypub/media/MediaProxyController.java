package dev.biribiri.elysium.activitypub.media;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.ProfileImage;
import dev.biribiri.elysium.content.MediaAttachment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Serves other servers' attachments, avatars, and headers that aren't kept here, by downloading them while the browser
 * waits, so that the browser doesn't fetch them from their server. One that can't be kept is redirected to its server.
 *
 * <p>Redirects aren't cached, since where one leads changes when a copy is kept, removed, or replaced, and the copy it
 * leads to is cached for good under its own name.
 */
@RestController
@Tag(name = "Media", description = "Other servers' media, served from here.")
class MediaProxyController {

    private final MediaDownloads downloads;
    private final InstanceProperties instance;

    MediaProxyController(MediaDownloads downloads, InstanceProperties instance) {
        this.downloads = downloads;
        this.instance = instance;
    }

    @GetMapping(MediaAttachment.PROXY_PATH + "{id}/{size}")
    @Operation(
            operationId = "mediaProxyAttachment",
            summary = "Get another server's attachment",
            description = """
                    Where a status's `url` and `preview_url` lead for an attachment from another server that isn't kept \
                    here. It's downloaded first, if it can be, and then the request is redirected to the copy. One that \
                    can't be downloaded, such as one too large or over its server's hourly limit, is redirected to its \
                    server.""",
            parameters = @Parameter(name = "size", description = """
                    `original`, or `small` for its preview, which is the original if it has none."""),
            responses = {
                    @ApiResponse(responseCode = "302", description = "Leads to the copy, or to its server's."),
                    @ApiResponse(responseCode = "404", description = """
                            No such attachment, or its status was deleted, or its author is suspended.""")
            })
    ResponseEntity<Void> attachment(@PathVariable String id, @PathVariable String size) {
        var small = MediaAttachment.PROXY_SMALL.equals(size);
        if (!small && !MediaAttachment.PROXY_ORIGINAL.equals(size)) {
            return ResponseEntity.notFound().build();
        }
        var found = Ids.parse(id).flatMap(downloads::downloadAttachmentNow);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var attachment = found.get();
        return redirect(attachment.keptUrl(instance, small).orElse(attachment.remoteUrl()));
    }

    @GetMapping(ProfileImage.PROXY_PATH + "{actorId}/{image}")
    @Operation(
            operationId = "mediaProxyProfileImage",
            summary = "Get an account on another server's avatar or header",
            description = """
                    Where an account's `avatar` and `header` lead for an account on another server whose image isn't \
                    kept here. It's downloaded as an attachment is.""",
            parameters = @Parameter(name = "image", description = "`avatar` or `header`."),
            responses = {
                    @ApiResponse(responseCode = "302", description = "Leads to the copy, or to its server's."),
                    @ApiResponse(responseCode = "404", description = """
                            No such account or image, or the account is suspended.""")
            })
    ResponseEntity<Void> profileImage(@PathVariable String actorId, @PathVariable String image) {
        return profileImage(actorId, image, false);
    }

    @GetMapping(ProfileImage.PROXY_PATH + "{actorId}/{image}/" + ProfileImage.STATIC)
    @Operation(
            operationId = "mediaProxyStaticProfileImage",
            summary = "Get the first frame of an account on another server's avatar or header",
            description = """
                    Where an account's `avatar_static` and `header_static` lead, like \
                    `GET /media_proxy/accounts/{actorId}/{image}`.""",
            parameters = @Parameter(name = "image", description = "`avatar` or `header`."),
            responses = {
                    @ApiResponse(responseCode = "302", description = "Leads to the copy, or to its server's."),
                    @ApiResponse(responseCode = "404", description = """
                            No such account or image, or the account is suspended.""")
            })
    ResponseEntity<Void> staticProfileImage(@PathVariable String actorId, @PathVariable String image) {
        return profileImage(actorId, image, true);
    }

    private ResponseEntity<Void> profileImage(String actorId, String image, boolean still) {
        var header = ProfileImage.HEADER.equals(image);
        if (!header && !ProfileImage.AVATAR.equals(image)) {
            return ResponseEntity.notFound().build();
        }
        var found = Ids.parse(actorId).flatMap(id -> downloads.downloadProfileImageNow(id, header));
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var kept = found.get();
        if (kept.path() == null || kept.isProxied()) {
            return kept.remoteUrl() == null ? ResponseEntity.notFound().build() : redirect(kept.remoteUrl());
        }
        return redirect(kept.url(instance, still));
    }

    private static ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(location)
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
