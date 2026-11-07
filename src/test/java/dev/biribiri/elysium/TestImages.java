package dev.biribiri.elysium;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.nio.StreamingGifWriter;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;

/**
 * Images for tests to upload.
 */
public final class TestImages {

    private TestImages() {
    }

    public static byte[] png(int width, int height) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /**
     * Each frame is a different color, so that none of them can be merged.
     */
    public static byte[] animatedGif(int frames, int width, int height) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var stream = new StreamingGifWriter(Duration.ofMillis(100), true, false)
                .prepareStream(out, BufferedImage.TYPE_INT_ARGB)) {
            for (var i = 0; i < frames; i++) {
                var frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                var graphics = frame.createGraphics();
                graphics.setColor(new Color((i * 40) % 256, 100, 200));
                graphics.fillRect(0, 0, width, height);
                graphics.dispose();
                stream.writeFrame(ImmutableImage.wrapAwt(frame));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        return out.toByteArray();
    }
}
