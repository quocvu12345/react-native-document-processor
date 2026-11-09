package vn.money24h.documentprocessor;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns camera lifecycle and copies scanner results into files owned by our caller. */
final class DocumentCapture extends BaseActivityEventListener {
    private static final int REQUEST_SCAN = 24861;
    private final ReactApplicationContext context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Promise pending;
    private boolean closed;
    private long startedAt;

    DocumentCapture(ReactApplicationContext context) {
        this.context = context;
        context.addActivityEventListener(this);
    }

    synchronized void scan(Promise promise) {
        if (closed || pending != null) {
            DocumentScanLog.event("camera.busy", "closed=" + closed);
            promise.reject("DOCUMENT_CAPTURE_BUSY", "Document camera is busy or closed.");
            return;
        }
        pending = promise;
        startedAt = SystemClock.elapsedRealtime();
        DocumentScanLog.event("camera.start", "format=JPEG mode=FULL");
        UiThreadUtil.runOnUiThread(() -> {
            if (!isActive(promise)) { return; }
            Activity activity = context.getCurrentActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                fail(promise, new IOException("No foreground activity for document camera."));
                return;
            }
            try {
                GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                        .build();
                GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                        .addOnSuccessListener(sender -> {
                            if (!isActive(promise)) { return; }
                            try {
                                DocumentScanLog.event("camera.ready", "elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt));
                                activity.startIntentSenderForResult(sender, REQUEST_SCAN, null, 0, 0, 0);
                            } catch (Exception error) {
                                fail(promise, error);
                            }
                        })
                        .addOnFailureListener(error -> fail(promise, error));
            } catch (Exception error) {
                fail(promise, error);
            }
        });
    }

    private synchronized boolean isActive(Promise promise) {
        return !closed && pending == promise;
    }

    private synchronized void fail(Promise promise, Exception error) {
        if (pending == promise) {
            pending = null;
            DocumentScanLog.error("camera.error", error);
            promise.reject("DOCUMENT_CAPTURE_ERROR", error.getMessage(), error);
        }
    }

    private synchronized boolean complete(Promise promise, String status, List<File> files) {
        if (!isActive(promise)) { return false; }
        WritableArray images = Arguments.createArray();
        for (File file : files) { images.pushString(Uri.fromFile(file).toString()); }
        WritableMap result = Arguments.createMap();
        result.putString("status", status);
        result.putArray("scannedImages", images);
        pending = null;
        DocumentScanLog.event("camera.complete", "status=" + status + " pages=" + files.size()
                + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt));
        promise.resolve(result);
        return true;
    }

    @Override
    public synchronized void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_SCAN || pending == null || closed) { return; }
        Promise promise = pending;
        DocumentScanLog.event("camera.result", "resultCode=" + resultCode);
        if (resultCode == Activity.RESULT_CANCELED) {
            complete(promise, "cancel", new ArrayList<>());
            return;
        }
        GmsDocumentScanningResult result = GmsDocumentScanningResult.fromActivityResultIntent(data);
        if (resultCode != Activity.RESULT_OK || result == null || result.getPages() == null
                || result.getPages().isEmpty()) {
            fail(promise, new IOException("Document camera returned no pages."));
            return;
        }
        List<GmsDocumentScanningResult.Page> pages = new ArrayList<>(result.getPages());
        DocumentScanLog.event("camera.copy.start", "pages=" + pages.size());
        worker.execute(() -> {
            long copyStartedAt = SystemClock.elapsedRealtime();
            List<File> files = new ArrayList<>();
            boolean transferred = false;
            try {
                byte[] buffer = new byte[64 * 1024];
                for (GmsDocumentScanningResult.Page page : pages) {
                    if (!isActive(promise)) { return; }
                    File file = File.createTempFile("document-capture-", ".jpg", context.getCacheDir());
                    files.add(file);
                    try (InputStream input = context.getContentResolver().openInputStream(page.getImageUri());
                         FileOutputStream output = new FileOutputStream(file)) {
                        if (input == null) { throw new IOException("Cannot read scanned page."); }
                        int count;
                        while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); }
                    }
                    if (file.length() == 0) { throw new IOException("Scanned page is empty."); }
                    DocumentScanLog.event("camera.copy.page", "page=" + files.size() + " bytes=" + file.length());
                }
                DocumentScanLog.event("camera.copy.complete", "pages=" + files.size()
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - copyStartedAt));
                transferred = complete(promise, "success", files);
            } catch (Exception error) {
                fail(promise, error);
            } finally {
                if (!transferred) {
                    for (File file : files) { file.delete(); }
                }
            }
        });
    }

    synchronized void invalidate() {
        if (closed) { return; }
        closed = true;
        if (pending != null) {
            pending.reject("DOCUMENT_CAPTURE_CANCELLED", "Document camera was closed.");
            pending = null;
        }
        context.removeActivityEventListener(this);
        worker.shutdown();
    }
}
