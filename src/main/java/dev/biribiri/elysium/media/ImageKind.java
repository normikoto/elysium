package dev.biribiri.elysium.media;

/**
 * What an image is for, which decides the size it's made. These are Mastodon's sizes, which clients lay out for.
 *
 * <p>Profile images are cropped to fill their shape exactly. Attachments keep their shape, and are only scaled down to fit
 * a number of pixels, which Mastodon gives as width × height. Fitting an area rather than a box treats a tall image and a
 * wide one the same.
 */
public enum ImageKind {
    AVATAR(400, 400, Fit.COVER),
    HEADER(1500, 500, Fit.COVER),
    ATTACHMENT(3840, 2160, Fit.WITHIN),
    ATTACHMENT_PREVIEW(640, 360, Fit.WITHIN);

    public enum Fit {
        /**
         * Scaled and cropped to exactly the kind's width and height.
         */
        COVER,
        /**
         * Scaled down to at most the kind's width × height in pixels, keeping its shape, and never scaled up.
         */
        WITHIN
    }

    private final int width;
    private final int height;
    private final Fit fit;

    ImageKind(int width, int height, Fit fit) {
        this.width = width;
        this.height = height;
        this.fit = fit;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Fit fit() {
        return fit;
    }

    public long pixels() {
        return (long) width * height;
    }
}
