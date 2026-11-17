package dev.biribiri.elysium.text;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders a status written in Markdown as the HTML that clients expect. URLs, mentions, and hashtags are linked as
 * {@link PlainText} links them, so they look the same whichever way a status was written. HTML in the text is shown rather
 * than kept, so the only markup is what's written here, and it's limited to what {@link RemoteHtml} keeps: other servers
 * sanitize what they're sent much as Mastodon does, so anything more would show here but be lost there.
 */
public final class Markdown {

    private static final String LINK = "nofollow noopener noreferrer";

    // Strikethrough, for ~~this~~, is the one extension, since del is the only tag Mastodon keeps that CommonMark lacks.
    // Autolinks aren't one, since Urls finds URLs in the text as it does for plain text.
    private static final List<Extension> EXTENSIONS = List.of(StrikethroughExtension.create());

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    // Private use characters, which Markdown treats as any other text, around a URL's index.
    private static final char PLACEHOLDER_START = '\uE000';
    private static final char PLACEHOLDER_END = '\uE001';
    private static final Pattern PLACEHOLDER = Pattern.compile("\uE000(\\d+)\uE001");

    /**
     * Where {@link #compact} drops white space, since it's only ever between blocks there.
     */
    private static final Set<String> CONTAINERS = Set.of("body", "blockquote", "ul", "ol", "li");

    private static final Set<String> BLOCKS = Set.of("p", "blockquote", "ul", "ol", "pre");

    private Markdown() {
    }

    /**
     * @param mentions where each mention links to, or null to leave it as text, as for an account that doesn't exist
     * @param tagBase  the base URL that hashtags link under, or null to leave them as text
     */
    public static String render(String text, Function<Mentions.Match, PlainText.MentionLink> mentions, URI tagBase) {
        var renderer = HtmlRenderer.builder()
                .extensions(EXTENSIONS)
                .escapeHtml(true)
                .sanitizeUrls(true)
                // People expect a new line where they pressed Enter, as in a plain text status.
                .softbreak("<br>")
                .attributeProviderFactory(context -> Markdown::limitAttributes)
                .nodeRendererFactory(context -> new LinkingRenderer(context, mentions, tagBase))
                .nodeRendererFactory(MastodonBlocks::new)
                .build();
        return compact(renderer.render(parse(text)));
    }

    /**
     * Parses the text with each URL that {@link Urls} finds kept whole. CommonMark would otherwise read a {@code _} or
     * {@code *} in one as emphasis, which would cut the URL short before it's linked. Each URL is swapped for a
     * placeholder that Markdown leaves alone, and put back everywhere a placeholder can end up, including code and link
     * titles. Text that already has the placeholder's characters is parsed as it is, rather than risk mixing them
     * up.
     */
    private static Node parse(String text) {
        // A link's destination, or an autolink, is read whole already, and Urls would run on past its closing bracket.
        var urls = Urls.find(text).stream()
                .filter(url -> url.start() == 0
                        || (text.charAt(url.start() - 1) != '(' && text.charAt(url.start() - 1) != '<'))
                .map(url -> new Urls.Match(url.start(), protectedEnd(text, url)))
                .toList();
        if (urls.isEmpty() || text.indexOf(PLACEHOLDER_START) >= 0 || text.indexOf(PLACEHOLDER_END) >= 0) {
            return PARSER.parse(text);
        }
        var found = new ArrayList<String>();
        var protectedText = new StringBuilder();
        var position = 0;
        for (var url : urls) {
            protectedText.append(text, position, url.start())
                    .append(PLACEHOLDER_START).append(found.size()).append(PLACEHOLDER_END);
            found.add(text.substring(url.start(), url.end()));
            position = url.end();
        }
        protectedText.append(text, position, text.length());
        var document = PARSER.parse(protectedText.toString());
        document.accept(new UrlRestorer(found));
        return document;
    }

    /**
     * Where a URL's protection ends: before Markdown that Urls takes as part of it, such as the backtick closing a code
     * span, or the end of a link's text. None of these can be in a URL unencoded. The URL is still linked as Urls finds it
     * once it's put back, so this only changes what Markdown reads.
     */
    private static int protectedEnd(String text, Urls.Match url) {
        var end = url.end();
        for (var i = url.start(); i < url.end(); i++) {
            var character = text.charAt(i);
            if (character == '`' || character == '<' || character == '>' || text.startsWith("](", i)) {
                end = i;
                break;
            }
        }
        return end;
    }

    private static String restore(String value, List<String> urls) {
        if (value == null || value.indexOf(PLACEHOLDER_START) < 0) {
            return value;
        }
        return PLACEHOLDER.matcher(value).replaceAll(match ->
                Matcher.quoteReplacement(urls.get(Integer.parseInt(match.group(1)))));
    }

    private static final class UrlRestorer extends AbstractVisitor {

        private final List<String> urls;

        private UrlRestorer(List<String> urls) {
            this.urls = urls;
        }

        @Override
        public void visit(Text node) {
            node.setLiteral(restore(node.getLiteral(), urls));
        }

        @Override
        public void visit(Code node) {
            node.setLiteral(restore(node.getLiteral(), urls));
        }

        @Override
        public void visit(FencedCodeBlock node) {
            node.setLiteral(restore(node.getLiteral(), urls));
        }

        @Override
        public void visit(IndentedCodeBlock node) {
            node.setLiteral(restore(node.getLiteral(), urls));
        }

        @Override
        public void visit(HtmlInline node) {
            node.setLiteral(restore(node.getLiteral(), urls));
        }

        @Override
        public void visit(HtmlBlock node) {
            node.setLiteral(restore(node.getLiteral(), urls));
        }

        @Override
        public void visit(Link node) {
            node.setDestination(restore(node.getDestination(), urls));
            node.setTitle(restore(node.getTitle(), urls));
            visitChildren(node);
        }

        @Override
        public void visit(Image node) {
            node.setDestination(restore(node.getDestination(), urls));
            node.setTitle(restore(node.getTitle(), urls));
            visitChildren(node);
        }
    }

    /**
     * The text that {@link #render} links mentions and hashtags in, with each piece on its own line. Mentions and hashtags
     * are looked up in this rather than in the source, so that one in code or in a link's text, which isn't linked, doesn't
     * notify anyone or tag the status either.
     */
    public static String linkableText(String text) {
        var pieces = new ArrayList<String>();
        parse(text).accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                pieces.add(text.getLiteral());
            }

            @Override
            public void visit(HtmlBlock block) {
                pieces.add(block.getLiteral());
            }

            @Override
            public void visit(Link link) {
            }

            @Override
            public void visit(Image image) {
            }
        });
        return String.join("\n", pieces);
    }

    /**
     * Drops the new lines that the renderer puts between blocks, which Mastodon's HTML doesn't have, and which a client
     * that keeps white space would show as blank lines. Those inside code are kept.
     */
    private static String compact(String html) {
        var document = Jsoup.parseBodyFragment(html);
        for (var node : document.body().getAllElements().stream()
                .filter(element -> CONTAINERS.contains(element.normalName()))
                .flatMap(element -> element.textNodes().stream())
                .filter(TextNode::isBlank)
                .filter(node -> !node.parent().nameIs("li") || nextToBlock(node))
                .toList()) {
            node.remove();
        }
        document.outputSettings().prettyPrint(false);
        return document.body().html();
    }

    /**
     * A list item's text can sit between two inline elements, where it's the space between two words.
     */
    private static boolean nextToBlock(TextNode node) {
        var previous = node.previousSibling();
        var next = node.nextSibling();
        return previous == null || next == null
                || previous instanceof Element element && BLOCKS.contains(element.normalName())
                || next instanceof Element element && BLOCKS.contains(element.normalName());
    }

    private static boolean insideLink(Node node) {
        for (var parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof Link || parent instanceof Image) {
                return true;
            }
        }
        return false;
    }

    /**
     * Links open in a new tab, and can't be followed by search engines, as other links in statuses can't. Only web links
     * are kept, as Mastodon keeps, and other attributes, such as a code block's language and a link's title, are dropped,
     * as Mastodon drops them.
     */
    private static void limitAttributes(Node node, String tagName, Map<String, String> attributes) {
        if (tagName.equals("a")) {
            var href = attributes.get("href");
            attributes.clear();
            var scheme = href == null ? "" : href.toLowerCase(Locale.ROOT);
            if (scheme.startsWith("https://") || scheme.startsWith("http://")) {
                attributes.put("href", href);
            }
            attributes.put("rel", LINK);
            attributes.put("target", "_blank");
        } else {
            // Mastodon keeps where a numbered list starts, so that "2024. A year" doesn't show as "1. A year".
            var start = tagName.equals("ol") ? attributes.get("start") : null;
            attributes.clear();
            if (start != null) {
                attributes.put("start", start);
            }
        }
    }

    private record LinkingRenderer(HtmlNodeRendererContext context, Function<Mentions.Match, PlainText.MentionLink> mentions,
                                   URI tagBase) implements NodeRenderer {

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Text.class, HtmlBlock.class);
        }

        @Override
        public void render(Node node) {
            var writer = context.getWriter();
            if (node instanceof HtmlBlock block) {
                // A block of HTML is shown as the text it was written as, so it's a paragraph like any other.
                writer.line();
                writer.raw("<p>" + block.getLiteral().strip().lines()
                        .map(line -> PlainText.renderLine(line, mentions, tagBase))
                        .collect(Collectors.joining("<br>")) + "</p>");
                writer.line();
            } else if (insideLink(node)) {
                writer.text(((Text) node).getLiteral());
            } else {
                writer.raw(PlainText.renderLine(((Text) node).getLiteral(), mentions, tagBase));
            }
        }
    }

    /**
     * What Mastodon's sanitizer would change, written as it would come out: headings as bold paragraphs, rather than
     * text that runs into the next paragraph, no rules, and images as links, with their descriptions as the links' text,
     * since clients and other servers don't show images in a status.
     */
    private record MastodonBlocks(HtmlNodeRendererContext context) implements NodeRenderer {

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Heading.class, ThematicBreak.class, Image.class);
        }

        @Override
        public void render(Node node) {
            var writer = context.getWriter();
            switch (node) {
                case Heading heading -> {
                    writer.line();
                    writer.tag("p");
                    writer.tag("strong");
                    renderChildren(heading);
                    writer.tag("/strong");
                    writer.tag("/p");
                    writer.line();
                }
                case Image image -> {
                    var href = context.urlSanitizer().sanitizeLinkUrl(image.getDestination());
                    var description = altText(image);
                    var shown = description.isBlank() ? image.getDestination() : description;
                    // An image inside a link, as in a badge, is only its text, since a link can't hold another.
                    if (insideLink(image)) {
                        writer.text(shown);
                        return;
                    }
                    writer.tag("a", context.extendAttributes(image, "a", Map.of("href", context.encodeUrl(href))));
                    writer.text(shown);
                    writer.tag("/a");
                }
                default -> {
                }
            }
        }

        private void renderChildren(Node parent) {
            for (var child = parent.getFirstChild(); child != null; child = child.getNext()) {
                context.render(child);
            }
        }

        private static String altText(Image image) {
            var text = new StringBuilder();
            image.accept(new AbstractVisitor() {
                @Override
                public void visit(Text node) {
                    text.append(node.getLiteral());
                }

                @Override
                public void visit(Code node) {
                    text.append(node.getLiteral());
                }
            });
            return text.toString().strip();
        }
    }
}
