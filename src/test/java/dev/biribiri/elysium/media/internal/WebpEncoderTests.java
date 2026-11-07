package dev.biribiri.elysium.media.internal;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;
import dev.biribiri.elysium.TestImages;
import dev.biribiri.elysium.media.ImageKind;
import dev.biribiri.elysium.media.InvalidImageException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebpEncoderTests {

    private final WebpEncoder encoder = new WebpEncoder();

    @Test
    void reencodesAPngAsAWebpCroppedToTheAvatarSize() throws IOException {
        var encoded = encoder.encode(image("png", 800, 600, false), ImageKind.AVATAR);

        var header = ImageHeader.read(encoded.image());
        assertThat(header.format()).isEqualTo(ImageHeader.ImageFormat.WEBP);
        assertThat(header.width()).isEqualTo(400);
        assertThat(header.height()).isEqualTo(400);
        assertThat(header.isAnimated()).isFalse();
        assertThat(encoded.still()).isNull();
    }

    @Test
    void cropsAHeaderToItsShape() throws IOException {
        var encoded = encoder.encode(image("png", 300, 300, false), ImageKind.HEADER);

        var header = ImageHeader.read(encoded.image());
        assertThat(header.width()).isEqualTo(1500);
        assertThat(header.height()).isEqualTo(500);
    }

    @Test
    void keepsTransparency() throws IOException {
        var encoded = encoder.encode(image("png", 100, 100, true), ImageKind.AVATAR);

        var decoded = ImmutableImage.loader().withImageReaders(java.util.List.of(new com.sksamuel.scrimage.webp.WebpImageReader()))
                .fromBytes(encoded.image());
        assertThat(decoded.pixel(0, 0).alpha()).isLessThan(255);
    }

    @Test
    void readsJpegAndWebp() throws IOException {
        assertThat(ImageHeader.read(encoder.encode(image("jpg", 50, 50, false), ImageKind.AVATAR).image()).width())
                .isEqualTo(400);
        var webp = ImmutableImage.wrapAwt(new BufferedImage(60, 30, BufferedImage.TYPE_INT_RGB)).bytes(WebpWriter.DEFAULT);
        assertThat(ImageHeader.read(encoder.encode(webp, ImageKind.HEADER).image()).height()).isEqualTo(500);
    }

    @Test
    void leavesOutMetadata() throws IOException {
        var marker = "where-this-photo-was-taken";
        var jpeg = withExif(image("jpg", 50, 50, false), marker);

        var encoded = encoder.encode(jpeg, ImageKind.AVATAR).image();

        assertThat(new String(encoded, StandardCharsets.ISO_8859_1)).doesNotContain(marker).doesNotContain("Exif");
    }

    @Test
    void keepsAnAnimatedGifAnimatedWithAStillOfItsFirstFrame() throws IOException {
        var gif = animatedGif(3, 120, 80);

        var encoded = encoder.encode(gif, ImageKind.AVATAR);

        var animation = ImageHeader.read(encoded.image());
        assertThat(animation.format()).isEqualTo(ImageHeader.ImageFormat.WEBP);
        assertThat(animation.frames()).isEqualTo(3);
        assertThat(animation.width()).isEqualTo(400);
        var still = ImageHeader.read(encoded.still());
        assertThat(still.isAnimated()).isFalse();
        assertThat(still.width()).isEqualTo(400);
    }

    @Test
    void keepsAnAnimatedWebpAnimated() throws IOException {
        var webp = encoder.encode(animatedGif(2, 50, 50), ImageKind.HEADER).image();

        var encoded = encoder.encode(webp, ImageKind.AVATAR);

        assertThat(ImageHeader.read(encoded.image()).frames()).isEqualTo(2);
        assertThat(encoded.still()).isNotNull();
    }

    @Test
    void refusesTooManyFrames() throws IOException {
        var gif = animatedGif(WebpEncoder.MAX_FRAMES + 1, 2, 2);

        assertThatThrownBy(() -> encoder.encode(gif, ImageKind.AVATAR))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("frames");
    }

    @Test
    void refusesAnImageThatClaimsTooManyPixelsBeforeDecodingIt() {
        var png = pngHeaderOnly(20_000, 20_000);

        assertThatThrownBy(() -> encoder.encode(png, ImageKind.AVATAR))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("megapixels");
    }

    @Test
    void refusesWhatIsntASupportedImage() {
        assertThatThrownBy(() -> encoder.encode("<svg></svg>".getBytes(StandardCharsets.UTF_8), ImageKind.AVATAR))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("PNG, JPEG, GIF or WebP");
        assertThatThrownBy(() -> encoder.encode(new byte[0], ImageKind.AVATAR))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void refusesACorruptImage() throws IOException {
        var png = image("png", 100, 100, false);
        var truncated = java.util.Arrays.copyOf(png, 60);

        assertThatThrownBy(() -> encoder.encode(truncated, ImageKind.AVATAR)).isInstanceOf(InvalidImageException.class);
    }

    private static byte[] image(String format, int width, int height, boolean transparent) throws IOException {
        var image = new BufferedImage(width, height, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(transparent ? new Color(255, 0, 0, 0) : Color.RED);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static byte[] animatedGif(int frames, int width, int height) throws IOException {
        return TestImages.animatedGif(frames, width, height);
    }

    /**
     * Inserts an APP1 segment holding an Exif TIFF header with no entries, followed by the marker, after the JPEG's start.
     */
    private static byte[] withExif(byte[] jpeg, String marker) {
        var payload = new ByteArrayOutputStream();
        payload.writeBytes("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
        payload.writeBytes(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        payload.writeBytes(marker.getBytes(StandardCharsets.ISO_8859_1));
        var segment = payload.toByteArray();
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write((segment.length + 2) >> 8);
        out.write((segment.length + 2) & 0xFF);
        out.writeBytes(segment);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /**
     * A PNG with only its signature and header chunk, which says how large it is.
     */
    private static byte[] pngHeaderOnly(int width, int height) {
        var data = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0)
                .put((byte) 0).put((byte) 0).array();
        var crc = new CRC32();
        crc.update("IHDR".getBytes(StandardCharsets.US_ASCII));
        crc.update(data);
        return ByteBuffer.allocate(8 + 4 + 4 + 13 + 4)
                .put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})
                .putInt(13)
                .put("IHDR".getBytes(StandardCharsets.US_ASCII))
                .put(data)
                .putInt((int) crc.getValue())
                .array();
    }
}
