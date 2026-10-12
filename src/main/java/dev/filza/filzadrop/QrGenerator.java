package dev.filza.filzadrop;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import javafx.scene.image. Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.EnumMap;
import java.util.Map;

public final class QrGenerator {
    private QrGenerator() {}

    public static Image generate(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            WritableImage image = new WritableImage(size, size);
            PixelWriter writer = image.getPixelWriter();

            Color fg = Color.web("#F3EEFF");
            Color bg = Color.web("#0F0B18");

            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    writer.setColor(x, y, matrix.get(x, y) ? fg : bg);
                }
            }
            return image;
        } catch (Exception e) {
            return new WritableImage(size, size);
        }
    }
}
