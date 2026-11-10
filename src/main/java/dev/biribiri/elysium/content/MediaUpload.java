package dev.biribiri.elysium.content;

import java.io.InputStream;

/**
 * A file someone uploads to attach to a status.
 *
 * @param thumbnail a still to show for a video or audio, or null
 * @param description what it shows, for people who can't see it, or null for none
 * @param focus       or null for the middle
 */
public record MediaUpload(InputStream file, InputStream thumbnail, String description, Focus focus) {
}
