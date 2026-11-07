package dev.biribiri.elysium.media.internal;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.nio.AnimatedGifReader;
import com.sksamuel.scrimage.nio.ImageSource;
import com.sksamuel.scrimage.nio.PngWriter;
import com.sksamuel.scrimage.webp.AnimatedWebpReader;
import com.sksamuel.scrimage.webp.Img2WebpHandler;
import com.sksamuel.scrimage.webp.WebpImageReader;
import com.sksamuel.scrimage.webp.WebpWriter;
import dev.biribiri.elysium.media.ImageKind;
import dev.biribiri.elysium.media.InvalidImageException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Re-encodes an upload as WebP at the size its kind needs. Re-encoding from decoded pixels is also what removes metadata,
 * such as where a photo was taken, since none of it is copied across.
 *
 * <p>The encoding is done by the libwebp tools that come with Scrimage, each run as a process. Decoding an animation holds
 * every frame in memory, so only a few run at once, and the rest wait.
 */
public final class WebpEncoder {

    /**
     * Enough for a 6000 × 5000 photo. Decoding needs four bytes for each pixel.
     */
    static final long MAX_PIXELS = 30_000_000;

    /**
     * Every frame of an animation is decoded at once, so they share a smaller budget: 100 frames of 500 × 500, say.
     */
    static final long MAX_ANIMATION_PIXELS = 25_000_000;

    static final int MAX_FRAMES = 300;

    private static final int QUALITY = 85;

    /**
     * Browsers play a frame shorter than this as 100 ms, which is what animations that set no delay expect.
     */
    private static final Duration SHORTEST_FRAME = Duration.ofMillis(20);
    private static final Duration DEFAULT_FRAME = Duration.ofMillis(100);

    private static final WebpWriter STILL_WRITER = WebpWriter.DEFAULT.withQ(QUALITY);

    private final Semaphore running = new Semaphore(2, true);

    /**
     * An encoded image. {@code still} is the first frame of an animation, and null for a still image.
     */
    public record Encoded(byte[] image, byte[] still) {
    }

    public Encoded encode(byte[] bytes, ImageKind kind) {
        var header = ImageHeader.read(bytes);
        if (header.pixels() > MAX_PIXELS) {
            throw new InvalidImageException("The image is larger than " + MAX_PIXELS / 1_000_000 + " megapixels");
        }
        if (header.frames() > MAX_FRAMES) {
            throw new InvalidImageException("The animation has more than " + MAX_FRAMES + " frames");
        }
        if (header.isAnimated() && header.pixels() * header.frames() > MAX_ANIMATION_PIXELS) {
            throw new InvalidImageException(
                    "The animation is too large. Its frames can have " + MAX_ANIMATION_PIXELS / 1_000_000 + " megapixels in all");
        }
        try {
            running.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        try {
            return header.isAnimated() ? encodeAnimation(bytes, header, kind) : new Encoded(encodeStill(bytes, header, kind), null);
        } finally {
            running.release();
        }
    }

    private static byte[] encodeStill(byte[] bytes, ImageHeader header, ImageKind kind) {
        var image = decode(() -> {
            var loader = ImmutableImage.loader().detectOrientation(true);
            if (header.format() == ImageHeader.ImageFormat.WEBP) {
                loader = loader.withImageReaders(List.of(new WebpImageReader()));
            }
            return loader.fromBytes(bytes);
        });
        return write(fit(image, kind));
    }

    /**
     * Scrimage's GIF and WebP animations have the same methods, but no type in common.
     */
    private record Animation(List<ImmutableImage> frames, List<Duration> delays, int loops) {

        Animation(List<ImmutableImage> frames, IntFunction<Duration> delay, int loops) {
            this(frames, IntStream.range(0, frames.size()).mapToObj(delay).toList(), loops);
        }
    }

    private static Encoded encodeAnimation(byte[] bytes, ImageHeader header, ImageKind kind) {
        var animation = decode(() -> {
            if (header.format() == ImageHeader.ImageFormat.GIF) {
                var gif = AnimatedGifReader.read(ImageSource.of(bytes));
                return new Animation(gif.getFrames(), gif::getDelay, gif.getLoopCount());
            }
            var webp = AnimatedWebpReader.read(ImageSource.of(bytes));
            return new Animation(webp.getFrames(), webp::getDelay, webp.getLoopCount());
        });
        if (animation.frames().isEmpty()) {
            throw new InvalidImageException("The animation has no frames");
        }
        return writeAnimation(animation, kind);
    }

    private static ImmutableImage fit(ImmutableImage image, ImageKind kind) {
        return image.cover(kind.width(), kind.height());
    }

    private static byte[] write(ImmutableImage image) {
        try {
            return image.bytes(STILL_WRITER);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Goes to {@code img2webp} directly, rather than through Scrimage's streaming writer, which can only loop forever or
     * play once. Each frame is fitted as it's written, so that only one is held at the size it's shown at, which can be
     * larger than the upload's.
     */
    private static Encoded writeAnimation(Animation animation, ImageKind kind) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("elysium-animation");
            var inputs = new ArrayList<Path>();
            byte[] still = null;
            for (var i = 0; i < animation.frames().size(); i++) {
                var fitted = fit(animation.frames().get(i), kind);
                if (i == 0) {
                    still = write(fitted);
                }
                var input = directory.resolve(i + ".png");
                Files.write(input, fitted.bytes(PngWriter.NoCompression));
                inputs.add(input);
            }
            var output = directory.resolve("out.webp");
            var milliseconds = animation.delays().stream()
                    .mapToInt(delay -> (int) (delay.compareTo(SHORTEST_FRAME) < 0 ? DEFAULT_FRAME : delay).toMillis())
                    .toArray();
            new Img2WebpHandler().convert(inputs, milliseconds, output, Math.max(animation.loops(), 0), QUALITY, -1, false);
            return new Encoded(Files.readAllBytes(output), still);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (directory != null) {
                deleteRecursively(directory);
            }
        }
    }

    private static void deleteRecursively(Path directory) {
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // A temporary file left behind is only wasted space.
        }
    }

    private interface Decoder<T> {
        T decode() throws IOException;
    }

    /**
     * A file whose header is fine can still be corrupt further on, and that's the uploader's problem, not the server's.
     */
    private static <T> T decode(Decoder<T> decoder) {
        try {
            return decoder.decode();
        } catch (IOException | RuntimeException e) {
            throw new InvalidImageException("The image can't be read", e);
        }
    }
}
