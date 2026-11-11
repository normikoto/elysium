package dev.biribiri.elysium.auth.internal;

import io.nayuki.qrcodegen.QrCode;

import java.util.Locale;

/**
 * Draws a QR code as SVG that goes straight into a page. It's drawn here rather than by a QR code service, which would be
 * sent the secret, and rather than as an image at its own URL, which browsers and proxies could cache.
 */
final class QrSvg {

    /**
     * The empty border that the QR code standard requires around the code, in modules.
     */
    private static final int QUIET_ZONE = 4;

    private QrSvg() {
    }

    static String of(String text) {
        var qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        var size = qr.size + QUIET_ZONE * 2;
        var path = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    path.append('M').append(x + QUIET_ZONE).append(',').append(y + QUIET_ZONE).append("h1v1h-1z");
                }
            }
        }
        // Always black on white, even in dark mode, because some scanners can't read a code with its colors reversed. The root
        // locale, since the default one's digits may not be ASCII.
        return String.format(Locale.ROOT, """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges" role="img" \
                aria-label="QR code for your authenticator app"><rect width="100%%" height="100%%" fill="#fff"/>\
                <path d="%s" fill="#000"/></svg>""", size, size, path);
    }
}
