package vn.money24h.documentprocessor;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Desktop JVM fixture generator; PDFKit independently parses/renders its output. */
public final class JpegPdfWriterSmoke {
    public static void main(String[] args) throws Exception {
        try (JpegPdfWriter empty = new JpegPdfWriter(new File(args[0], "empty.pdf"))) {
            try {
                empty.finish();
                throw new AssertionError("Empty PDF accepted");
            } catch (IOException expected) { }
        }
        try (JpegPdfWriter writer = new JpegPdfWriter(new File(args[0], "smoke.pdf"))) {
            for (int index = 0; index < 30; index++) {
                boolean landscape = index % 2 == 0;
                int width = landscape ? 300 : 200;
                int height = landscape ? 200 : 300;
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D canvas = image.createGraphics();
                canvas.setColor(landscape ? Color.RED : Color.GREEN);
                canvas.fillRect(0, 0, width, height);
                canvas.setColor(Color.BLUE);
                canvas.fillRect(0, height / 2, width, height / 2);
                canvas.dispose();
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                ImageIO.write(image, "jpg", jpeg);
                writer.append(jpeg.toByteArray(), width, height);
            }
            writer.finish();
            try {
                writer.append(new byte[] {1}, 1, 1);
                throw new AssertionError("Closed PDF accepted another page");
            } catch (IOException expected) { }
        }
        System.out.println("PDF writer: 30 pages generated; empty/closed-session guards passed.");
    }
}
