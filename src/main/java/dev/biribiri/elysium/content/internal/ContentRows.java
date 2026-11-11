package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.QuoteApproval;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;

import java.net.URI;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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

    static URI uriOrNull(String value) {
        return value == null ? null : URI.create(value);
    }

    static Instant instantOrNull(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * @return null if the row has neither column set
     */
    static QuoteApproval quoteApproval(ResultSet rs) throws SQLException {
        var automatic = rs.getArray("quote_automatic");
        var manual = rs.getArray("quote_manual");
        return automatic == null && manual == null ? null : new QuoteApproval(audiences(automatic), audiences(manual));
    }

    private static Set<QuoteApproval.Audience> audiences(Array array) throws SQLException {
        if (array == null) {
            return Set.of();
        }
        return Arrays.stream((String[]) array.getArray())
                .map(value -> QuoteApproval.Audience.valueOf(value.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toSet());
    }

    /**
     * @return the audiences as a PostgreSQL array literal
     */
    static String array(Set<QuoteApproval.Audience> audiences) {
        return audiences.stream().map(QuoteApproval.Audience::value).sorted().collect(Collectors.joining(",", "{", "}"));
    }
}
