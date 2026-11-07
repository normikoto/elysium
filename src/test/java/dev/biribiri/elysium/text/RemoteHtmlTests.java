package dev.biribiri.elysium.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteHtmlTests {

    @Test
    void keepsMastodonsMarkup() {
        var html = "<p>Hi <span class=\"h-card\"><a href=\"https://elysium.test/@alice\" class=\"u-url mention\">"
                + "@<span>alice</span></a></span> <a href=\"https://example.com/a/long/path\" rel=\"nofollow noopener\" "
                + "target=\"_blank\"><span class=\"invisible\">https://</span><span class=\"ellipsis\">example.com/a</span>"
                + "<span class=\"invisible\">/long/path</span></a></p><p>Two<br>lines</p>";

        assertThat(RemoteHtml.sanitize(html)).isEqualTo(
                "<p>Hi <span class=\"h-card\"><a href=\"https://elysium.test/@alice\" class=\"u-url mention\" "
                        + "rel=\"nofollow noopener noreferrer\" target=\"_blank\">@<span>alice</span></a></span> "
                        + "<a href=\"https://example.com/a/long/path\" rel=\"nofollow noopener noreferrer\" "
                        + "target=\"_blank\"><span class=\"invisible\">https://</span><span class=\"ellipsis\">"
                        + "example.com/a</span><span class=\"invisible\">/long/path</span></a></p><p>Two<br>lines</p>");
    }

    @Test
    void removesScriptsAndEventHandlers() {
        var html = "<p onclick=\"alert(1)\">Hi<script>alert(2)</script><img src=x onerror=alert(3)></p>"
                + "<style>body{display:none}</style><iframe src=\"https://example.com\"></iframe>";

        assertThat(RemoteHtml.sanitize(html)).isEqualTo("<p>Hi</p>");
    }

    @Test
    void removesLinksThatArentToWebPages() {
        assertThat(RemoteHtml.sanitize("<a href=\"javascript:alert(1)\">x</a> <a href=\"data:text/html,x\">y</a>"))
                .isEqualTo("<a rel=\"nofollow noopener noreferrer\" target=\"_blank\">x</a> "
                        + "<a rel=\"nofollow noopener noreferrer\" target=\"_blank\">y</a>");
    }

    @Test
    void removesStylesAndUnknownClasses() {
        assertThat(RemoteHtml.sanitize("<p style=\"color:red\" class=\"x\"><span class=\"x hashtag\" id=\"a\">#t</span></p>"))
                .isEqualTo("<p><span class=\"hashtag\">#t</span></p>");
    }

    @Test
    void keepsTheTextOfOtherElements() {
        assertThat(RemoteHtml.sanitize("<div><table><tr><td>cell</td></tr></table><marquee>moving</marquee></div>"))
                .isEqualTo("cellmoving");
    }

    @Test
    void turnsHeadingsIntoParagraphs() {
        assertThat(RemoteHtml.sanitize("<h1>Title</h1><p>Body</p>")).isEqualTo("<p><strong>Title</strong></p><p>Body</p>");
    }

    @Test
    void dropsWhatCantBeStored() {
        assertThat(RemoteHtml.sanitize("<p>a\0b\uD800c</p>")).isEqualTo("<p>abc</p>");
    }

    @Test
    void readsTextWithLineBreaks() {
        assertThat(RemoteHtml.plainText("<p>One<br>two</p><p>three &amp; <a href=\"https://x.test\">four</a></p>"))
                .isEqualTo("One\ntwo\n\nthree & four");
    }

    @Test
    void readsInlineText() {
        assertThat(RemoteHtml.inlineText("<p>Content <b>warning</b></p><p>here</p>")).isEqualTo("Content warning here");
    }
}
