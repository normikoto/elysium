package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.MediaAttachment;

import java.net.URI;
import java.util.List;

/**
 * An attachment of a status, as Mastodon sends one: a {@code Document} whatever it is, with the file's type in
 * {@code mediaType}.
 *
 * @param name       its description, as plain text, or null for none
 * @param focalPoint where to keep in view when it's cropped, as x and y from -1 to 1, or null for the middle
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Document(String type, String mediaType, URI url, String name, String blurhash, Integer width,
                       Integer height, List<Double> focalPoint) {

    public static Document of(MediaAttachment attachment, InstanceProperties instance) {
        return new Document(
                "Document",
                attachment.contentType(),
                attachment.url(instance),
                attachment.description(),
                attachment.blurhash(),
                attachment.width(),
                attachment.height(),
                attachment.focus() == null ? null : List.of(attachment.focus().x(), attachment.focus().y()));
    }
}
