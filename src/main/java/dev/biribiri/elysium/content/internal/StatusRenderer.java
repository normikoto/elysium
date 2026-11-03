package dev.biribiri.elysium.content.internal;

import org.springframework.web.util.HtmlUtils;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders plain text as the HTML that clients expect in a status. Everything is escaped, and the only markup in the
 * result is what this class adds, so the output is safe to show as is.
 */
public final class StatusRenderer {

    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\n{2,}");

    private StatusRenderer() {
    }

    public static String render(String text) {
        return PARAGRAPH_BREAK.splitAsStream(text)
                .map(paragraph -> "<p>" + renderLines(paragraph) + "</p>")
                .collect(Collectors.joining());
    }

    private static String renderLines(String paragraph) {
        return paragraph.lines().map(StatusRenderer::renderLine).collect(Collectors.joining("<br>"));
    }

    private static String renderLine(String line) {
        var html = new StringBuilder();
        var position = 0;
        for (var url : StatusUrls.find(line)) {
            html.append(escape(line.substring(position, url.start())))
                    .append(link(line.substring(url.start(), url.end())));
            position = url.end();
        }
        return html.append(escape(line.substring(position))).toString();
    }

    private static String link(String url) {
        var escaped = escape(url);
        return "<a href=\"" + escaped + "\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" + escaped + "</a>";
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8");
    }
}
