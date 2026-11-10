package dev.biribiri.elysium.media.internal;

import dev.biribiri.elysium.TestImages;
import dev.biribiri.elysium.TestMedia;
import dev.biribiri.elysium.media.MediaKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class MediaSnifferTests {

    @Test
    void recognizesImagesWhichAreServedAsWebp() throws IOException {
        var png = MediaSniffer.sniff(TestImages.png(10, 10)).orElseThrow();
        assertThat(png.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(png.extension()).isEqualTo("webp");
        assertThat(MediaSniffer.sniff(TestImages.animatedGif(2, 10, 10)).orElseThrow().contentType()).isEqualTo("image/gif");
        assertThat(MediaSniffer.sniff(HexFormat.of().parseHex("ffd8ffe000104a464946")).orElseThrow().contentType())
                .isEqualTo("image/jpeg");
    }

    @Test
    void recognizesVideoAndAudioByTheirSignatures() {
        assertThat(sniffed(TestMedia.mp4(100))).isEqualTo("video/mp4 mp4 VIDEO");
        assertThat(sniffed(TestMedia.m4a(100))).isEqualTo("audio/mp4 m4a AUDIO");
        assertThat(sniffed(TestMedia.mp3(100))).isEqualTo("audio/mpeg mp3 AUDIO");
        assertThat(sniffed(ascii("\0\0\0\u0014ftypqt  \0\0\0\0qt  "))).isEqualTo("video/quicktime mov VIDEO");
        assertThat(sniffed(concat(HexFormat.of().parseHex("1a45dfa39f4286810142f7810142f2810442f381084282"),
                ascii("\u0084webm")))).isEqualTo("video/webm webm VIDEO");
        assertThat(sniffed(ascii("OggS\0\u0002"))).isEqualTo("audio/ogg ogg AUDIO");
        assertThat(sniffed(ascii("fLaC\0\0\0\""))).isEqualTo("audio/flac flac AUDIO");
        assertThat(sniffed(ascii("RIFF$\0\0\0WAVEfmt "))).isEqualTo("audio/wav wav AUDIO");
        assertThat(sniffed(HexFormat.of().parseHex("fffb9064"))).isEqualTo("audio/mpeg mp3 AUDIO");
    }

    @Test
    void refusesWhatIsNeitherWhateverItClaims() {
        assertThat(MediaSniffer.sniff(TestMedia.html())).isEmpty();
        assertThat(MediaSniffer.sniff(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"))).isEmpty();
        assertThat(MediaSniffer.sniff(ascii("%PDF-1.7"))).isEmpty();
        // An MP4 container with a brand that isn't one of the accepted ones, and ADTS AAC, which isn't MP3.
        assertThat(MediaSniffer.sniff(ascii("\0\0\0\u0014ftypheic\0\0\0\0"))).isEmpty();
        assertThat(MediaSniffer.sniff(HexFormat.of().parseHex("fff15080"))).isEmpty();
        assertThat(MediaSniffer.sniff(new byte[0])).isEmpty();
        assertThat(MediaSniffer.sniff(Arrays.copyOf(TestMedia.mp4(24), 6))).isEmpty();
    }

    private static String sniffed(byte[] head) {
        var sniffed = MediaSniffer.sniff(head).orElseThrow();
        return sniffed.contentType() + " " + sniffed.extension() + " " + sniffed.kind();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        var all = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
