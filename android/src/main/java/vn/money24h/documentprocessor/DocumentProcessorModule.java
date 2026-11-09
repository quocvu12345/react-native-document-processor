package vn.money24h.documentprocessor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.json.JSONArray;

@ReactModule(name = DocumentProcessorModule.NAME)
public final class DocumentProcessorModule extends ReactContextBaseJavaModule {
    static final String NAME = "DocumentProcessor";
    private static final Pattern DOCUMENT = Pattern.compile("^doc\\|[^|]+\\|[0-9]+$");
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // Accessed only on worker. ML Kit callbacks run independently of this executor.
    private final Map<String, Session> sessions = new HashMap<>();
    private final DocumentCapture capture;
    private final AtomicBoolean invalidated = new AtomicBoolean();

    private static final class Session {
        final File directory;
        final File pdf;
        final JpegPdfWriter writer;
        final BarcodeScanner scanner;
        final long startedAt = SystemClock.elapsedRealtime();
        int pageCount;
        boolean finished;
        boolean closed;

        Session(File directory) throws IOException {
            this.directory = directory;
            pdf = new File(directory, "document.pdf");
            writer = new JpegPdfWriter(pdf);
            try {
                scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build());
            } catch (RuntimeException error) {
                writer.close();
                throw error;
            }
        }

        void close() {
            if (closed) { return; }
            closed = true;
            scanner.close();
            try { writer.close(); } catch (IOException ignored) { }
        }
    }

    interface Operation { void run() throws Exception; }

    public DocumentProcessorModule(ReactApplicationContext context) {
        super(context);
        capture = new DocumentCapture(context);
        DocumentScanLog.event("processor.ready", "pipeline=image->QR+PDF");
    }

    @ReactMethod
    public void logEvent(String event, String details) {
        DocumentScanLog.event("js." + event, details);
    }

    @ReactMethod
    public void scanDocument(ReadableMap options, Promise promise) {
        capture.scan(promise);
    }

    @ReactMethod
    public void scanBarcodes(String uri, ReadableArray formats, Promise promise) {
        execute("barcode.scan", promise, () -> {
            long startedAt = SystemClock.elapsedRealtime();
            DocumentScanLog.event("barcode.start", "formats=" + formats);
            Bitmap bitmap = decode(uri);
            try {
                WritableMap result = BarcodeImageScanner.scan(bitmap, formats);
                DocumentScanLog.event("barcode.complete", "elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt)
                        + " width=" + bitmap.getWidth() + " height=" + bitmap.getHeight() + " result=" + result);
                promise.resolve(result);
            } finally {
                bitmap.recycle();
            }
        });
    }

    @Override
    public String getName() { return NAME; }

    private void execute(String name, Promise promise, Operation operation) {
        try {
            worker.execute(() -> {
                try {
                    operation.run();
                } catch (Exception error) {
                    DocumentScanLog.error(name + ".error", error);
                    promise.reject("DOCUMENT_PROCESSOR_ERROR", error.getMessage(), error);
                }
            });
        } catch (RejectedExecutionException error) {
            DocumentScanLog.error(name + ".closed", error);
            promise.reject("DOCUMENT_PROCESSOR_CLOSED", "Document processor has closed.", error);
        }
    }

    @ReactMethod
    public void beginSession(Promise promise) {
        execute("session.begin", promise, () -> {
            String id = UUID.randomUUID().toString();
            File directory = new File(getReactApplicationContext().getCacheDir(), "document-processor-" + id);
            if (!directory.mkdirs()) { throw new IOException("Cannot create document cache."); }
            try {
                sessions.put(id, new Session(directory));
                DocumentScanLog.event("session.begin", "session=" + id);
                promise.resolve(id);
            } catch (Exception error) {
                deleteDirectory(directory);
                throw error;
            }
        });
    }

    private Session activeSession(String id) throws IOException {
        Session session = sessions.get(id);
        if (session == null || session.finished) { throw new IOException("Session is closed or missing."); }
        return session;
    }

    private Bitmap decode(String path) throws IOException {
        Uri uri = path.startsWith("/") ? Uri.fromFile(new File(path)) : Uri.parse(path);
        ImageDecoder.Source source;
        if ("file".equals(uri.getScheme())) {
            source = ImageDecoder.createSource(new File(uri.getPath()));
        } else if ("content".equals(uri.getScheme())) {
            source = ImageDecoder.createSource(getReactApplicationContext().getContentResolver(), uri);
        } else {
            throw new IOException("Expected a local image file or content URI.");
        }
        // ImageDecoder applies EXIF orientation, including mirrored images.
        return ImageDecoder.decodeBitmap(source, (decoder, info, input) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            double scale = Math.min(1.0, 4096.0 / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, (int) Math.round(width * scale)),
                    Math.max(1, (int) Math.round(height * scale)));
        });
    }

    private boolean readQr(Session session, Bitmap bitmap, Set<String> values) {
        try {
            // Wait on the dedicated worker, never the UI/native modules thread.
            // Do not recycle the input until ML Kit finishes using it.
            for (Barcode barcode : Tasks.await(session.scanner.process(InputImage.fromBitmap(bitmap, 0)))) {
                String value = barcode.getRawValue();
                if (value != null && !value.isEmpty()) { values.add(value); }
            }
            return true;
        } catch (Exception error) {
            DocumentScanLog.error("qr.decode.error", error);
            return false;
        }
    }

    private static boolean hasDocument(Set<String> values) {
        for (String value : values) {
            if (DOCUMENT.matcher(value).matches()) { return true; }
        }
        return false;
    }

    private static Bitmap resizeOpaque(Bitmap source, int maximum) {
        double scale = Math.min(1.0, (double) maximum / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, null, new Rect(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
        result.setHasAlpha(false);
        return result;
    }

    @ReactMethod
    public void appendPage(String id, String uri, boolean scanQr, Promise promise) {
        execute("page.append", promise, () -> {
            long startedAt = SystemClock.elapsedRealtime();
            Session session = activeSession(id);
            String trace = "session=" + id + " page=" + (session.pageCount + 1);
            DocumentScanLog.event("page.start", trace + " scanQr=" + scanQr);
            Bitmap bitmap = decode(uri);
            DocumentScanLog.event("page.decoded", trace + " width=" + bitmap.getWidth()
                    + " height=" + bitmap.getHeight() + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt));
            try {
                long qrStartedAt = SystemClock.elapsedRealtime();
                Set<String> values = new LinkedHashSet<>();
                boolean hasError = scanQr && !readQr(session, bitmap, values);
                int cropAttempts = 0;
                if (scanQr && !hasDocument(values)) {
                    int width = (int) Math.ceil(bitmap.getWidth() * 0.6);
                    int height = (int) Math.ceil(bitmap.getHeight() * 0.6);
                    int right = bitmap.getWidth() - width;
                    int bottom = bitmap.getHeight() - height;
                    int[][] offsets = {{right, 0}, {0, 0}, {right, bottom}, {0, bottom}};
                    for (int[] offset : offsets) {
                        cropAttempts++;
                        Bitmap crop = Bitmap.createBitmap(bitmap, offset[0], offset[1], width, height);
                        try {
                            if (!readQr(session, crop, values)) { hasError = true; }
                        } finally {
                            if (crop != bitmap) { crop.recycle(); }
                        }
                    }
                }
                WritableMap page = Arguments.createMap();
                String status = hasError ? "error" : (values.isEmpty() ? "not-detected" : "decoded");
                DocumentScanLog.event("page.qr", trace + " status=" + status + " scanQr=" + scanQr
                        + " cropAttempts=" + cropAttempts + " elapsedMs=" + (SystemClock.elapsedRealtime() - qrStartedAt)
                        + " rawValues=" + new JSONArray(values));
                WritableArray rawValues = Arguments.createArray();
                for (String value : values) { rawValues.pushString(value); }
                page.putInt("page", session.pageCount + 1);
                page.putArray("rawValues", rawValues);
                page.putString("status", status);
                if (hasDocument(values)) {
                    Bitmap preview = resizeOpaque(bitmap, 1000);
                    File file = new File(session.directory, "page-" + (session.pageCount + 1) + ".jpg");
                    try (FileOutputStream output = new FileOutputStream(file)) {
                        if (preview.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                            page.putString("previewUri", Uri.fromFile(file).toString());
                        }
                    } catch (IOException error) {
                        DocumentScanLog.error("page.preview.error", error);
                        // Optional preview failure must preserve successfully decoded QR data.
                    } finally {
                        preview.recycle();
                    }
                }
                Bitmap pdfImage = resizeOpaque(bitmap, 3000);
                try {
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    if (!pdfImage.compress(Bitmap.CompressFormat.JPEG, 92, jpeg)) {
                        throw new IOException("Cannot encode PDF page.");
                    }
                    session.writer.append(jpeg.toByteArray(), pdfImage.getWidth(), pdfImage.getHeight());
                } finally {
                    pdfImage.recycle();
                }
                session.pageCount++;
                DocumentScanLog.event("page.complete", trace + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt));
                promise.resolve(page);
            } finally {
                bitmap.recycle();
            }
        });
    }

    @ReactMethod
    public void finishSession(String id, Promise promise) {
        execute("pdf.finish", promise, () -> {
            Session session = activeSession(id);
            session.writer.finish();
            session.finished = true;
            session.close();
            WritableMap result = Arguments.createMap();
            result.putString("pdfUri", Uri.fromFile(session.pdf).toString());
            result.putString("directoryUri", Uri.fromFile(session.directory).toString());
            DocumentScanLog.event("pdf.complete", "session=" + id + " pages=" + session.pageCount
                    + " bytes=" + session.pdf.length() + " elapsedMs=" + (SystemClock.elapsedRealtime() - session.startedAt));
            promise.resolve(result);
        });
    }

    @ReactMethod
    public void releaseSession(String id, boolean deleteFiles, Promise promise) {
        execute("session.release", promise, () -> {
            Session session = sessions.remove(id);
            if (session != null) {
                session.close();
                if (deleteFiles) { deleteDirectory(session.directory); }
            }
            DocumentScanLog.event("session.release", "session=" + id + " deleteFiles=" + deleteFiles);
            promise.resolve(null);
        });
    }

    private static void deleteDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File file : children) { file.delete(); }
        }
        directory.delete();
    }

    @Override
    public void invalidate() {
        if (!invalidated.compareAndSet(false, true)) { return; }
        DocumentScanLog.event("processor.invalidate", "cleanup=true");
        capture.invalidate();
        // Queue cleanup after any in-flight ML Kit task; never interrupt/recycle its bitmap.
        worker.execute(() -> {
            for (Session session : sessions.values()) {
                session.close();
                deleteDirectory(session.directory);
            }
            sessions.clear();
        });
        worker.shutdown();
        super.invalidate();
    }
}
