package com.payments.gateway.checkout.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;
import java.util.Optional;

/**
 * Renders a QR code as inline SVG on the server. Nothing but module coordinates reaches the page, so the payload
 * needs no escaping, and the page needs no script or image source: the strict CSP stays as it is (ADR-021).
 */
final class QrSvg {

    private static final int QUIET_ZONE = 4;
    private static final int PIXELS = 240;

    private QrSvg() {
    }

    static Optional<String> render(String payload, String label) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, QUIET_ZONE));
        } catch (WriterException | IllegalArgumentException e) {
            return Optional.empty();
        }
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    path.append('M').append(x).append(',').append(y).append("h1v1h-1z");
                }
            }
        }
        return Optional.of("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + matrix.getWidth() + " "
                + matrix.getHeight() + "\" width=\"" + PIXELS + "\" height=\"" + PIXELS + "\" role=\"img\" aria-label=\""
                + label + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>"
                + "<path fill=\"#000\" d=\"" + path + "\"/></svg>");
    }
}
