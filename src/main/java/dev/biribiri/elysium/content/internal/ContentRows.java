package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.Visibility;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;

final class ContentRows {

    /**
     * Reads {@link Visibility} by its stored value, since the default conversion would look for a constant of that name.
     */
    static final ConversionService CONVERSIONS = conversions();

    private ContentRows() {
    }

    private static ConversionService conversions() {
        var conversions = new DefaultConversionService();
        conversions.addConverter(String.class, Visibility.class, Visibility::fromValue);
        return conversions;
    }
}
