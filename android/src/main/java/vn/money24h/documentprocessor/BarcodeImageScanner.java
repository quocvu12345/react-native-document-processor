package vn.money24h.documentprocessor;

import android.graphics.Bitmap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Stateless image decoding on the processor worker; no document session/files. */
final class BarcodeImageScanner {
    private static final Map<String, Integer> FORMATS = new LinkedHashMap<>();
    static {
        FORMATS.put("qr", Barcode.FORMAT_QR_CODE);
        FORMATS.put("ean-13", Barcode.FORMAT_EAN_13);
        FORMATS.put("ean-8", Barcode.FORMAT_EAN_8);
        FORMATS.put("upc-a", Barcode.FORMAT_UPC_A);
        FORMATS.put("upc-e", Barcode.FORMAT_UPC_E);
        FORMATS.put("code-128", Barcode.FORMAT_CODE_128);
        FORMATS.put("code-39", Barcode.FORMAT_CODE_39);
        FORMATS.put("code-93", Barcode.FORMAT_CODE_93);
        FORMATS.put("codabar", Barcode.FORMAT_CODABAR);
        FORMATS.put("itf", Barcode.FORMAT_ITF);
        FORMATS.put("pdf-417", Barcode.FORMAT_PDF417);
        FORMATS.put("aztec", Barcode.FORMAT_AZTEC);
        FORMATS.put("data-matrix", Barcode.FORMAT_DATA_MATRIX);
    }

    private BarcodeImageScanner() { }

    static WritableMap scan(Bitmap bitmap, ReadableArray formats) throws Exception {
        if (formats == null || formats.size() == 0) {
            throw new IllegalArgumentException("At least one barcode format is required.");
        }
        int mask = 0;
        for (int i = 0; i < formats.size(); i++) {
            Integer format = FORMATS.get(formats.getString(i));
            if (format == null) { throw new IllegalArgumentException("Unsupported barcode format."); }
            mask |= format;
        }
        BarcodeScanner scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(mask).build());
        try {
            WritableArray codes = Arguments.createArray();
            Set<String> values = new LinkedHashSet<>();
            Set<String> uniqueCodes = new LinkedHashSet<>();
            for (Barcode barcode : Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)))) {
                String value = barcode.getRawValue();
                if (value == null || value.isEmpty()) { continue; }
                for (Map.Entry<String, Integer> format : FORMATS.entrySet()) {
                    if (format.getValue() != barcode.getFormat() || (mask & format.getValue()) == 0) { continue; }
                    if (uniqueCodes.add(format.getKey() + "\n" + value)) {
                        WritableMap code = Arguments.createMap();
                        code.putString("value", value);
                        code.putString("format", format.getKey());
                        codes.pushMap(code);
                        values.add(value);
                    }
                    break;
                }
            }
            WritableArray rawValues = Arguments.createArray();
            for (String value : values) { rawValues.pushString(value); }
            WritableMap result = Arguments.createMap();
            result.putArray("values", rawValues);
            result.putArray("codes", codes);
            return result;
        } finally {
            scanner.close();
        }
    }
}
