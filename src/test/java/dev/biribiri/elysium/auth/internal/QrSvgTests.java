package dev.biribiri.elysium.auth.internal;

import io.nayuki.qrcodegen.QrCode;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

class QrSvgTests {

    private static final String URI = TotpCodes.otpAuthUri(new byte[20], "elysium.example", "alice@elysium.example");

    @Test
    void isWellFormedSvgWithAQuietZone() throws Exception {
        var svg = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(QrSvg.of(URI))))
                .getDocumentElement();
        var size = QrCode.encodeText(URI, QrCode.Ecc.MEDIUM).size + 8;

        assertThat(svg.getTagName()).isEqualTo("svg");
        assertThat(svg.getAttribute("viewBox")).isEqualTo("0 0 " + size + " " + size);
        assertThat(svg.getElementsByTagName("path").getLength()).isOne();
    }

    @Test
    void drawsEveryDarkModule() {
        var qr = QrCode.encodeText(URI, QrCode.Ecc.MEDIUM);
        var dark = 0;
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                dark += qr.getModule(x, y) ? 1 : 0;
            }
        }

        assertThat(QrSvg.of(URI).split("h1v1h-1z", -1)).hasSize(dark + 1);
        // The top left module is always dark, and starts after the quiet zone.
        assertThat(QrSvg.of(URI)).contains("d=\"M4,4h1v1h-1z");
    }
}
