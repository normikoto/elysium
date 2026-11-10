/**
 * Files that people upload, kept on local disk and served under {@code /media}: profile images in {@link
 * dev.biribiri.elysium.media.Images}, and what's attached to posts in {@link dev.biribiri.elysium.media.MediaFiles}.
 */
@ApplicationModule(allowedDependencies = {})
package dev.biribiri.elysium.media;

import org.springframework.modulith.ApplicationModule;
