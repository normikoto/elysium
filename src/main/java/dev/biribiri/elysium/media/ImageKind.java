package dev.biribiri.elysium.media;

/**
 * What an image is for, which decides the size it's cropped to. These are Mastodon's sizes, which clients lay out for.
 */
public enum ImageKind {
    AVATAR(400, 400),
    HEADER(1500, 500);

    private final int width;
    private final int height;

    ImageKind(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }
}
