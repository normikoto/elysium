package dev.biribiri.elysium.text;

import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Function;
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

    /**
     * Where a mention links to.
     *
     * @param href the account's profile
     * @param acct the account's full address, without the leading {@code @}, shown when hovering over the mention
     */
    public record MentionLink(URI href, String acct) {
    }

    public static String render(String text) {
        return render(text, mention -> null, null);
    }

    /**
     * @param mentions where each mention links to, or null to leave it as text, as for an account that doesn't exist
     * @param tagBase  the base URL that hashtags link under, or null to leave them as text
     */
    public static String render(String text, Function<Mentions.Match, MentionLink> mentions, URI tagBase) {
        return PARAGRAPH_BREAK.splitAsStream(text)
                .map(paragraph -> "<p>" + renderLines(paragraph, mentions, tagBase) + "</p>")
                .collect(Collectors.joining());
    }

    /**
     * The paragraph that a quote starts with, as Mastodon writes it, for servers and clients that don't show quotes. Clients
     * that do can hide it by its class.
     */
    public static String quoteInline(URI quoted) {
        return "<p class=\"quote-inline\">RE: " + link(quoted.toString(), LINK) + "</p>";
    }

    /**
     * Renders a profile field's value, which is shown inline, so it has no paragraph. Its links say they're the author's
     * own with {@code rel="me"}, which is what lets the linked site and other servers check that they link back.
     */
    public static String renderFieldValue(String text) {
        return text.lines().map(line -> renderLine(line, ME_LINK, mention -> null, null)).collect(Collectors.joining(" "));
    }

    private static String renderLines(String paragraph, Function<Mentions.Match, MentionLink> mentions, URI tagBase) {
        return paragraph.lines().map(line -> renderLine(line, LINK, mentions, tagBase)).collect(Collectors.joining("<br>"));
    }

    private record Span(int start, int end, String html) {
    }

    private static String renderLine(
            String line, String rel, Function<Mentions.Match, MentionLink> mentions, URI tagBase) {
        var spans = new ArrayList<Span>();
        var urls = Urls.find(line);
        for (var url : urls) {
            spans.add(new Span(url.start(), url.end(), link(line.substring(url.start(), url.end()), rel)));
        }
        // Mentions never overlap a URL, since Mentions leaves out those inside one.
        for (var mention : Mentions.find(line)) {
            var target = mentions.apply(mention);
            if (target != null) {
                spans.add(new Span(mention.start(), mention.end(), mention(mention.username(), target)));
            }
        }
        // Hashtags leave out those inside a URL too. None should overlap a mention, but the spans mustn't if one did.
        if (tagBase != null) {
            for (var tag : Hashtags.find(line, urls)) {
                if (spans.stream().noneMatch(span -> tag.start() < span.end() && span.start() < tag.end())) {
                    spans.add(new Span(tag.start(), tag.end(), hashtag(tag.name(), tagBase)));
                }
            }
        }
        spans.sort(Comparator.comparingInt(Span::start));
        var html = new StringBuilder();
        var position = 0;
        for (var span : spans) {
            html.append(escape(line.substring(position, span.start()))).append(span.html());
            position = span.end();
        }
        return html.append(escape(line.substring(position))).toString();
    }

    /**
     * Mastodon's markup, which clients look for to open the account rather than the link. Only the username is shown, as
     * Mastodon shows it, so that a status full of mentions stays readable.
     */
    private static String mention(String username, MentionLink target) {
        return "<span class=\"h-card\" translate=\"no\"><a href=\"" + escape(target.href().toString())
                + "\" class=\"u-url mention\" title=\"@" + escape(target.acct()) + "\">@<span>" + escape(username)
                + "</span></a></span>";
    }

    /**
     * Mastodon's markup, which clients look for to open the tag's timeline rather than the link. The name is shown as
     * written, and the link uses the normalized one.
     */
    private static String hashtag(String name, URI tagBase) {
        return "<a href=\"" + escape(Hashtags.url(tagBase, Hashtags.normalize(name)).toString())
                + "\" class=\"mention hashtag\" rel=\"tag\">#<span>" + escape(name) + "</span></a>";
    }

    private static String link(String url, String rel) {
        var escaped = escape(url);
        return "<a href=\"" + escaped + "\" rel=\"" + rel + "\" target=\"_blank\">" + escaped + "</a>";
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text, "UTF-8");
    }
}
