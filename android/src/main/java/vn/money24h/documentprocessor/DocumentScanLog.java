package vn.money24h.documentprocessor;

import android.util.Log;

/** Opt-in diagnostics, including in release: adb shell setprop log.tag.DocumentScan DEBUG. */
final class DocumentScanLog {
    private static final String TAG = "DocumentScan";

    private DocumentScanLog() { }

    static void event(String event, String details) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) { return; }
        String message = details == null ? "" : details;
        // Stay below Logcat's UTF-8 entry limit, including Vietnamese/QR payloads.
        int chunks = Math.max(1, (message.length() + 799) / 800);
        for (int index = 0; index < chunks; index++) {
            String prefix = event + (chunks > 1 ? " [" + (index + 1) + "/" + chunks + "]" : "");
            Log.d(TAG, prefix + " " + message.substring(index * 800, Math.min(message.length(), (index + 1) * 800)));
        }
    }

    static void error(String event, Exception error) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            event(event, Log.getStackTraceString(error));
        }
    }
}
