package dev.biribiri.elysium.text;

import org.springframework.web.util.HtmlUtils;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders plain text as the HTML that clients expect in a status or a profile. Everything is escaped, and the only markup
 * in the result is what this class adds, so the output is safe to show as is.
 */
public final class PlainText {

    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\n{2,}");
    private static final String LINK = "nofollow noopener noreferrer";
    private static final String ME_LINK = "me " + LINK;

    private PlainText() {
    }

    public static String render(String text) {
        return PARAGRAPH_BREAK.splitAsStream(text)
                .map(paragraph -> "<p>" + renderLines(paragraph) + "</p>")
                .collect(Collectors.joining());
    }

    /**
     * Renders a profile field's value, which is shown inline, so it has no paragraph. Its links say they're the author's
     * own with {@code rel="me"}, which is what lets the linked site and other servers check that they link back.
     */
    public static String renderFieldValue(String text) {
        return text.lines().map(line -> renderLine(line, ME_LINK)).collect(Collectors.joining(" "));
    }

    private static String renderLines(String paragraph) {
        return paragraph.lines().map(line -> renderLine(line, LINK)).collect(Collectors.joining("<br>"));
    }

    private static String renderLine(String line, String rel) {
        var html = new StringBuilder();
        var position = 0;
        for (var url : Urls.find(line)) {
            html.append(escape(line.substring(position, url.start())))
                    .append(link(line.substring(url.start(), url.end()), rel));
            position = url.end();
        }
        return html.append(escape(line.substring(position))).toString();
    }

    private static String link(String url, String rel) {
        var escaped = escape(url);
        return "<a href=\"" + escaped + "\" rel=\"" + rel + "\" target=\"_blank\">" + escaped + "</a>";
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8");
    }
}
