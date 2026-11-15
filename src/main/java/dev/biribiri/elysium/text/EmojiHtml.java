package dev.biribiri.elysium.text;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.Map;

/**
 * Shows custom emoji as images in HTML that this server renders for its own pages. What's stored, and what clients and
 * other servers get, keeps the shortcode as text, as Mastodon's does, since they draw emoji from a status's list.
 */
public final class EmojiHtml {

    private EmojiHtml() {
    }

    /**
     * Only text is changed, so that a shortcode in a link's {@code href} or in a tag stays as it is.
     *
     * @param html   HTML that's already safe to show
     * @param emojis image URLs by shortcode, without colons
     */
    public static String render(String html, Map<String, String> emojis) {
        if (html == null || emojis.isEmpty() || !html.contains(":")) {
            return html;
        }
        var document = Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false);
        var texts = new ArrayList<TextNode>();
        document.body().traverse((node, depth) -> {
            if (node instanceof TextNode text) {
                texts.add(text);
            }
        });
        texts.forEach(text -> replace(text, emojis));
        return document.body().html();
    }

    /**
     * For text that's shown as text, such as a display name.
     */
    public static String renderText(String text, Map<String, String> emojis) {
        return render(new TextNode(text).outerHtml(), emojis);
    }

    private static void replace(TextNode text, Map<String, String> emojis) {
        var value = text.getWholeText();
        var replaced = new ArrayList<Node>();
        var position = 0;
        for (var match : Shortcodes.find(value)) {
            var url = emojis.get(match.name());
            if (url == null) {
                continue;
            }
            if (match.start() > position) {
                replaced.add(new TextNode(value.substring(position, match.start())));
            }
            var code = ":" + match.name() + ":";
            replaced.add(new Element("img")
                    .attr("class", "custom-emoji")
                    .attr("src", url)
                    .attr("alt", code)
                    .attr("title", code)
                    .attr("draggable", "false"));
            position = match.end();
        }
        if (replaced.isEmpty()) {
            return;
        }
        if (position < value.length()) {
            replaced.add(new TextNode(value.substring(position)));
        }
        for (var node : replaced) {
            text.before(node);
        }
        text.remove();
    }
}
