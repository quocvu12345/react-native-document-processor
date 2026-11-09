package vn.money24h.documentprocessor;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BridgeReactContext;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.soloader.OpenSourceMergedSoMapping;
import com.facebook.soloader.SoLoader;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Runs the actual JNI bridge objects, ML Kit decoder, bitmap/PDF code on a device. */
public class DocumentProcessorDeviceTest {
    private Context context;
    private DocumentProcessorModule module;
    private final List<File> files = new ArrayList<>();
    private final List<String> sessions = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SoLoader.init(context, OpenSourceMergedSoMapping.INSTANCE);
        module = new DocumentProcessorModule(new BridgeReactContext(context));
    }

    @After
    public void tearDown() throws Exception {
        for (String session : sessions) { call(promise -> module.releaseSession(session, true, promise)); }
        module.invalidate();
        for (File file : files) { delete(file); }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) { for (File child : children) { delete(child); } }
        file.delete();
    }

    private Object call(Consumer<Promise> action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> value = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        Promise promise = (Promise) Proxy.newProxyInstance(Promise.class.getClassLoader(),
                new Class<?>[] {Promise.class}, (proxy, method, args) -> {
                    if (method.getName().equals("resolve")) {
                        value.set(args[0]);
                        done.countDown();
                    } else if (method.getName().equals("reject")) {
                        error.set(java.util.Arrays.toString(args));
                        done.countDown();
                    }
                    return null;
                });
        action.accept(promise);
        assertTrue("Native promise timed out", done.await(60, TimeUnit.SECONDS));
        if (error.get() != null) { throw new IllegalStateException(error.get()); }
        return value.get();
    }

    private String begin() throws Exception {
        String session = (String) call(module::beginSession);
        sessions.add(session);
        return session;
    }

    private File page(String... codes) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        for (int index = 0; index < codes.length; index++) {
            BitMatrix matrix = new MultiFormatWriter().encode(codes[index], BarcodeFormat.QR_CODE, 300, 300);
            for (int y = 0; y < 300; y++) {
                for (int x = 0; x < 300; x++) {
                    if (matrix.get(x, y)) { bitmap.setPixel(x + 80 + index * 500, y + 100, Color.BLACK); }
                }
            }
        }
        File file = File.createTempFile("document-fixture-", ".jpg", context.getCacheDir());
        files.add(file);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output));
        } finally { bitmap.recycle(); }
        return file;
    }

    private ReadableMap append(String session, File image, boolean qr) throws Exception {
        return (ReadableMap) call(promise -> module.appendPage(session, Uri.fromFile(image).toString(), qr, promise));
    }

    private int renderPageCount(String uri) throws Exception {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(Uri.parse(uri).getPath()),
                ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer pdf = new PdfRenderer(fd)) {
            Bitmap bitmap = Bitmap.createBitmap(400, 560, Bitmap.Config.ARGB_8888);
            try (PdfRenderer.Page page = pdf.openPage(0)) {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                // JPEG/PdfRenderer color conversion can map white to #fdfdfd.
                int corner = bitmap.getPixel(0, 0);
                assertTrue(Color.red(corner) > 245 && Color.green(corner) > 245
                        && Color.blue(corner) > 245 && Color.alpha(corner) == 255);
                int darkPixels = 0;
                for (int y = 0; y < bitmap.getHeight(); y += 4) {
                    for (int x = 0; x < bitmap.getWidth(); x += 4) {
                        if (Color.red(bitmap.getPixel(x, y)) < 80) { darkPixels++; }
                    }
                }
                assertTrue("PDF lost the QR image content", darkPixels > 100);
            } finally { bitmap.recycle(); }
            return pdf.getPageCount();
        }
    }

    private ReadableMap scan(File image, String... formats) throws Exception {
        WritableArray requested = Arguments.createArray();
        for (String format : formats) { requested.pushString(format); }
        return (ReadableMap) call(promise -> module.scanBarcodes(Uri.fromFile(image).toString(), requested, promise));
    }

    private File barcodeImage(String payload, BarcodeFormat format) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(payload, format, 900, 300);
        Bitmap bitmap = Bitmap.createBitmap(1100, 500, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) { bitmap.setPixel(x + 100, y + 100, Color.BLACK); }
            }
        }
        File file = File.createTempFile("barcode fixture ", ".jpg", context.getCacheDir());
        files.add(file);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output));
        } finally { bitmap.recycle(); }
        return file;
    }

    @Test
    public void imageScannerReadsMultipleQrAndExifRotationWithoutPdfOrDeletingInputs() throws Exception {
        File image = page("doc|d|0030358", "https://24hmoney.vn");
        ExifInterface exif = new ExifInterface(image.getPath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
        exif.saveAttributes();
        ReadableMap result = scan(image, "qr");
        assertEquals(2, result.getArray("values").size());
        assertTrue(result.getArray("values").toArrayList().contains("doc|d|0030358"));
        assertTrue(result.getArray("values").toArrayList().contains("https://24hmoney.vn"));
        assertEquals("qr", result.getArray("codes").getMap(0).getString("format"));
        assertFalse(result.hasKey("pdfUri"));
        assertTrue(image.exists());
        ReadableMap repeated = scan(page("0030358", "0030358"), "qr");
        assertEquals(1, repeated.getArray("values").size());
        assertEquals(1, repeated.getArray("codes").size());
    }

    @Test
    public void imageScannerPreservesBookBarcodesAndRestrictsQrOnly() throws Exception {
        File book = barcodeImage("9780201379624", BarcodeFormat.EAN_13);
        ReadableMap result = scan(book, "qr", "ean-13");
        assertEquals("9780201379624", result.getArray("values").getString(0));
        assertEquals("ean-13", result.getArray("codes").getMap(0).getString("format"));
        assertEquals(0, scan(book, "qr").getArray("values").size());
        ReadableMap number = scan(barcodeImage("0030358", BarcodeFormat.CODE_128), "code-128");
        assertEquals("0030358", number.getArray("values").getString(0));
        assertEquals("code-128", number.getArray("codes").getMap(0).getString("format"));
    }

    @Test
    public void imageScannerDistinguishesBlankFromInvalidAndRecoversAfterErrors() throws Exception {
        ReadableMap blank = scan(page(), "qr");
        assertEquals(0, blank.getArray("values").size());
        assertEquals(0, blank.getArray("codes").size());
        File broken = File.createTempFile("broken-image-", ".jpg", context.getCacheDir());
        files.add(broken);
        try {
            scan(broken, "qr");
            fail("Invalid image accepted");
        } catch (IllegalStateException expected) { }
        File image = page("0030358");
        for (String[] formats : new String[][] {{}, {"unknown"}}) {
            try {
                scan(image, formats);
                fail("Invalid formats accepted");
            } catch (IllegalStateException expected) { }
        }
        assertEquals("0030358", scan(image, "qr").getArray("values").getString(0));
    }

    @Test
    public void readsActualQrCodesAndCreatesOrderedPdfWithoutDeletingCaptures() throws Exception {
        String session = begin();
        File first = page("doc|d|0030358");
        File second = page("doc|d|0030358", "doc|d|0030359");
        ReadableMap one = append(session, first, true);
        assertEquals("decoded", one.getString("status"));
        assertEquals("doc|d|0030358", one.getArray("rawValues").getString(0));
        assertTrue(new File(Uri.parse(one.getString("previewUri")).getPath()).exists());
        ReadableMap two = append(session, second, true);
        assertEquals(2, two.getInt("page"));
        assertTrue(two.getArray("rawValues").toArrayList().contains("doc|d|0030359"));
        assertEquals(2, two.getArray("rawValues").size());
        ReadableMap blank = append(session, page(), true);
        assertEquals("not-detected", blank.getString("status"));
        ReadableMap output = (ReadableMap) call(promise -> module.finishSession(session, promise));
        assertEquals(3, renderPageCount(output.getString("pdfUri")));
        File directory = new File(Uri.parse(output.getString("directoryUri")).getPath());
        files.add(directory);
        call(promise -> module.releaseSession(session, false, promise));
        assertTrue(directory.exists());
        assertTrue(first.exists());
        assertTrue(second.exists());
    }

    @Test
    public void writesThirtyPagesAndSkipsQrWhenDisabled() throws Exception {
        String session = begin();
        File image = page("doc|d|0030358");
        for (int index = 0; index < 30; index++) {
            ReadableMap page = append(session, image, false);
            assertEquals(index + 1, page.getInt("page"));
            assertEquals(0, page.getArray("rawValues").size());
            assertFalse(page.hasKey("previewUri"));
        }
        ReadableMap output = (ReadableMap) call(promise -> module.finishSession(session, promise));
        assertEquals(30, renderPageCount(output.getString("pdfUri")));
        File directory = new File(Uri.parse(output.getString("directoryUri")).getPath());
        call(promise -> module.releaseSession(session, true, promise));
        assertFalse(directory.exists());
        assertTrue(image.exists());
    }

    @Test
    public void rejectsEmptyAndBrokenPagesAndCleansPartialOutput() throws Exception {
        String session = begin();
        try {
            call(promise -> module.finishSession(session, promise));
            fail("Empty PDF accepted");
        } catch (IllegalStateException expected) { }
        File image = page("doc|d|0030358");
        append(session, image, true);
        try {
            append(session, new File(context.getCacheDir(), "missing-document.jpg"), true);
            fail("Broken page accepted");
        } catch (IllegalStateException expected) { }
        File directory = new File(context.getCacheDir(), "document-processor-" + session);
        assertTrue(directory.exists());
        call(promise -> module.releaseSession(session, true, promise));
        assertFalse(directory.exists());
        assertTrue(image.exists());
    }

    @Test
    public void cameraWithoutForegroundActivityRejectsAndCanRetry() throws Exception {
        for (int index = 0; index < 2; index++) {
            try {
                call(promise -> module.scanDocument(Arguments.createMap(), promise));
                fail("Camera launched without activity");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("No foreground activity"));
            }
        }
    }
}
