package dev.biribiri.elysium.text;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Makes HTML from another server safe to show to clients, which display a status's {@code content} as is. What's kept is
 * close to what Mastodon keeps, so that statuses look the same here as on Mastodon, and nothing can run a script, load a
 * resource, or change how a client's page looks.
 */
public final class RemoteHtml {

    private static final String LINK = "nofollow noopener noreferrer";

    /**
     * Mastodon's own classes, which its web client and others use to shorten links and show mentions and hashtags, and to
     * hide the link that a quote carries for servers that don't show quotes.
     */
    private static final Set<String> CLASSES =
            Set.of("h-card", "mention", "hashtag", "u-url", "invisible", "ellipsis", "quote-inline");

    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "span", "a", "del", "s", "pre", "code", "em", "strong", "b", "i", "u", "ul", "ol", "li",
                    "blockquote")
            .addAttributes("a", "href", "class")
            .addAttributes("span", "class")
            .addAttributes("p", "class")
            .addProtocols("a", "href", "http", "https")
            .addEnforcedAttribute("a", "rel", LINK)
            .addEnforcedAttribute("a", "target", "_blank");

    private RemoteHtml() {
    }

    public static String sanitize(String html) {
        var dirty = Jsoup.parseBodyFragment(storable(html));
        // Headings would be dropped to plain text, which runs them into the paragraph after them.
        dirty.body().select("h1, h2, h3, h4, h5, h6").forEach(heading -> {
            heading.tagName("strong");
            heading.wrap("<p></p>");
        });
        var clean = new Cleaner(SAFELIST).clean(dirty);
        clean.body().select("[class]").forEach(RemoteHtml::keepKnownClasses);
        clean.outputSettings().prettyPrint(false);
        return clean.body().html();
    }

    /**
     * The text that sanitized HTML shows, with paragraphs and line breaks kept as new lines.
     */
    public static String plainText(String html) {
        var document = Jsoup.parseBodyFragment(html);
        document.body().select("br").forEach(lineBreak -> lineBreak.replaceWith(new TextNode("\n")));
        document.body().select("p").forEach(paragraph -> paragraph.appendText("\n\n"));
        return document.body().wholeText().strip();
    }

    /**
     * Reduces HTML to text on one line, for what clients show as plain text, such as a spoiler text.
     */
    public static String inlineText(String html) {
        return plainText(sanitize(html)).replaceAll("\\s+", " ").strip();
    }

    private static void keepKnownClasses(Element element) {
        var kept = Arrays.stream(element.className().split("\\s+"))
                .filter(CLASSES::contains)
                .collect(Collectors.joining(" "));
        if (kept.isEmpty()) {
            element.removeAttr("class");
        } else {
            element.attr("class", kept);
        }
    }

    /**
     * PostgreSQL refuses NUL, and would store an unpaired surrogate as "?", so both are dropped.
     */
    private static String storable(String value) {
        var result = new StringBuilder(value.length());
        for (var i = 0; i < value.length(); i++) {
            var character = value.charAt(i);
            if (Character.isHighSurrogate(character) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                result.append(character).append(value.charAt(++i));
            } else if (character != '\0' && !Character.isSurrogate(character)) {
                result.append(character);
            }
        }
        return result.toString();
    }
}
