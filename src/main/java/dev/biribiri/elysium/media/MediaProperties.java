package dev.biribiri.elysium.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * Size limits aren't here, because each kind of upload has its own: they belong to what the upload is for.
 *
 * @param directory where uploaded files are kept. It has to persist, and be backed up along with the database, which
 *                  refers to the files in it.
 */
@ConfigurationProperties("elysium.media")
public record MediaProperties(@DefaultValue("media") Path directory) {

    public MediaProperties {
        directory = directory.toAbsolutePath().normalize();
    }
}
