package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.AttachmentType;
import dev.biribiri.elysium.content.MediaAttachment;

/**
 * An attachment as a page shows it. Only a copy kept here is embedded: one that's only on another server is a link, so
 * that a visitor's browser doesn't fetch from it without being asked.
 *
 * @param kind {@code image}, {@code video}, {@code audio}, or {@code link}
 * @param src  the copy kept here, which is null for a link
 * @param href where it can be opened
 */
record AttachmentView(String kind, String src, String preview, String href, String description) {

    static AttachmentView of(MediaAttachment attachment, InstanceProperties instance) {
        var href = attachment.url(instance).toString();
        var type = attachment.shownType();
        if (!attachment.isCached() || attachment.rejected() || type == AttachmentType.UNKNOWN) {
            return new AttachmentView("link", null, null, href, attachment.description());
        }
        var preview = attachment.previewUrl(instance).map(Object::toString).orElse(null);
        return new AttachmentView(type.value(), href, preview, href, attachment.description());
    }
}
