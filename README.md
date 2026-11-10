# react-native-document-processor

Phiên bản **0.2.0** — thư viện React Native chụp tài liệu, tạo PDF từ ảnh và đọc
QR/barcode bằng native trên iOS và Android.

- [Repository](https://github.com/quocvu12345/react-native-document-processor)
- [Package npm](https://www.npmjs.com/package/react-native-document-processor)
- Source của phiên bản này: tag `v0.2.0`.

## Cài đặt

```sh
yarn add react-native-document-processor@0.2.0 --exact
# Hoặc: npm install react-native-document-processor@0.2.0 --save-exact
pod install --project-directory=ios
```

Yêu cầu Android API 28+, iOS 15.1+ và React Native có autolinking. Đã kiểm tra
trong ứng dụng host dùng React Native 0.83. Camera Android cần Google Play services;
model ML Kit đọc barcode được đóng gói sẵn. iOS cần khai báo
`NSCameraUsageDescription` trong `Info.plist` của ứng dụng.

Rebuild native app sau khi cài hoặc nâng cấp thư viện. Reload JavaScript hoặc
cập nhật OTA không thêm được native module vào binary đã cài. Thư viện cần
native build riêng, chẳng hạn Expo development build.

Metadata giấy phép hiện là `UNLICENSED`.

## Thay đổi trong 0.2.0

- Sửa lỗi không đọc được QR trên iOS Simulator khi Vision không tạo được
  inference context: yêu cầu chỉ đọc QR sử dụng Vision revision 1 trên Simulator.
- Thiết bị iOS thật tiếp tục dùng revision mặc định. API JavaScript giữ nguyên.
- Bản sửa được đóng gói trực tiếp trong source native của npm.

## Chụp tài liệu và tạo PDF

`scanDocument` mở camera native: VisionKit trên iOS và ML Kit Document Scanner
trên Android. Người dùng có thể phát hiện biên, crop và xác nhận các trang.

```ts
import {
  processDocumentImages,
  scanDocument,
} from 'react-native-document-processor';
import RNFS from 'react-native-fs';

const capture = await scanDocument({ croppedImageQuality: 100 });
if (capture.status === 'cancel') return;

let output: Awaited<ReturnType<typeof processDocumentImages>> | undefined;
try {
  output = await processDocumentImages(capture.scannedImages, {
    onProgress: (completed, total) => updateProgress(completed, total),
    isCancelled: () => hasLeftScreen,
  });
  // output.pdfUri: PDF để lưu hoặc upload
  // output.pages: mã QR, trạng thái và preview của từng trang
  // output.directoryUri: thư mục chứa PDF và preview, do caller sở hữu
  await consumeDocument(output);
} finally {
  // Chỉ dọn output sau khi mọi thao tác lưu/upload/hiển thị đã hoàn tất.
  const temporaryPaths = [
    ...capture.scannedImages,
    ...(output ? [output.directoryUri] : []),
  ];
  await Promise.all(
    temporaryPaths.map(path => RNFS.unlink(path).catch(() => undefined)),
  );
}
```

`updateProgress`, `hasLeftScreen` và `consumeDocument` là logic của ứng dụng host.
Ví dụ dùng `react-native-fs` để dọn file; thư viện này không phải dependency của
package và có thể được thay bằng API filesystem của ứng dụng.

`croppedImageQuality` nhận giá trị từ 0 đến 100; Android giữ JPEG gốc của scanner.
Mỗi lần chụp trả về `{ status: 'success' | 'cancel', scannedImages: string[] }`.
iOS trả tối đa 24 trang trong một lần chụp. Ứng dụng có thể gọi lại camera và gộp
các ảnh theo thứ tự trước khi tạo PDF.

`processDocumentImages` trả về các trang đánh số từ 1:

```ts
{
  pdfUri: 'file:///cache/session/document.pdf',
  directoryUri: 'file:///cache/session',
  pages: [
    {
      page: 1,
      rawValues: ['doc|d|0030358'],
      status: 'decoded', // 'decoded' | 'not-detected' | 'error'
      previewUri: 'file:///cache/session/page-1.jpg', // tùy chọn
    },
  ],
}
```

Dùng `{ scanQr: false }` khi chỉ cần PDF. Khi đó native bỏ qua đọc QR và tạo
preview. Native không xóa ảnh đầu vào. Sau thành công, caller chịu trách nhiệm
xóa output và ảnh chụp khi không còn sử dụng. Nếu hủy hoặc có lỗi trước khi
chuyển quyền sở hữu, wrapper tự giải phóng output của session.

Hủy được kiểm tra giữa các trang và trước/sau khi đóng PDF. Trang đang được
Vision/ML Kit xử lý sẽ hoàn tất trước khi dọn tài nguyên. Lỗi đọc QR nằm trong
trạng thái từng trang; lỗi decode ảnh hoặc ghi PDF hủy cả session để tránh trả
PDF thiếu trang. Lỗi tạo preview không làm mất mã đã đọc được.

## Luồng xử lý native

```text
Ảnh đã chụp
  → decode và chuẩn hóa hướng EXIF
  → đọc QR trên bitmap
  → tạo preview và ghi trang PDF
  → trả PDF URI, mã QR và trạng thái từng trang về JavaScript
```

Các trang được xử lý tuần tự trên worker. Không truyền bitmap/base64 qua bridge
và không cần render lại PDF vừa tạo để đọc QR.

- iOS dùng ImageIO, Vision, Core Graphics và autorelease pool theo từng trang.
- Android dùng ImageDecoder, ML Kit barcode-scanning bundled 17.3.0 và PDF writer
  ghi JPEG trực tiếp; camera dùng play-services-mlkit-document-scanner 16.0.0.
- Ảnh đầu vào được giới hạn cạnh dài 4096 px; PDF tối đa 3000 px, JPEG 92%;
  preview tối đa 1000 px, JPEG 85%. Không phóng to ảnh nhỏ.
- Luồng xử lý tài liệu thử thêm bốn vùng chồng lấn 60% khi chưa thấy payload
  `doc|...|<số>`. Kết quả vẫn chứa các chuỗi QR thô để ứng dụng tự parse, đối chiếu
  và gộp mã theo nghiệp vụ.

## Đọc QR/barcode từ ảnh

```ts
import { scanBarcodes, scanQrImage } from 'react-native-document-processor';

const qr = await scanQrImage('/path/to/image.jpg');
const all = await scanBarcodes('file:///path/to/image.jpg');
const selected = await scanBarcodes('content://provider/image', {
  formats: ['qr', 'ean-13'],
});
// { values: ['0030358'], codes: [{ value: '0030358', format: 'qr' }] }
```

API chạy trên worker, giới hạn ảnh 4096 px và chuẩn hóa EXIF. Không mở camera,
tạo PDF hoặc xóa ảnh đầu vào. Hỗ trợ đường dẫn tuyệt đối, `file://` và thêm
`content://` trên Android; không nhận URL mạng hoặc base64.

Không phát hiện mã trả `{ values: [], codes: [] }`. Ảnh hỏng hoặc lỗi decoder
reject Promise. Kết quả hỗ trợ nhiều mã, loại mã trùng và giữ nguyên chuỗi,
kể cả số 0 đầu.

Các format: `qr`, `ean-13`, `ean-8`, `upc-a`, `upc-e`, `code-128`, `code-39`,
`code-93`, `codabar`, `itf`, `pdf-417`, `aztec`, `data-matrix`. iOS quy đổi UPC-A
từ EAN-13 có số 0 đầu khi yêu cầu UPC-A.

Package không render PDF có sẵn. Để đọc QR trong PDF local, ứng dụng cần render
lần lượt từng trang thành ảnh bằng một thư viện PDF, rồi gọi `scanQrImage` và
xóa ảnh tạm sau khi xử lý. Giữ tệp PDF gốc để lưu hoặc upload.

## Phát triển và kiểm tra

Clone repository và dùng dependency `file:` trong ứng dụng host để thử source
local. Chạy autolinking/pod install rồi rebuild native khi thay đổi iOS/Android.

Repository chứa:

- `src/__tests__/index.test.ts`: kiểm tra API, format, camera, thứ tự trang,
  hủy, lỗi native và quyền sở hữu file. Chạy với Jest preset React Native của
  ứng dụng host; cấu hình Jest để bao gồm thư mục source của thư viện.
- `tests/check-pdf-writer.sh`: tạo PDF 30 trang bằng Java rồi đọc/render bằng
  PDFKit để kiểm tra số trang, thứ tự, kích thước và hướng ảnh.
- Android instrumentation tests cho ML Kit, PDF, EXIF, nhiều QR, barcode,
  cleanup và xử lý camera khi không có foreground activity.

```sh
# Tại repository thư viện, cần macOS, Xcode và JDK
bash tests/check-pdf-writer.sh

# Tại thư mục android của ứng dụng host đã autolink, cần thiết bị ADB
./gradlew :react-native-document-processor:connectedReleaseAndroidTest
```

Nếu JDK không nằm trong PATH, đặt `DOCUMENT_PROCESSOR_JAVAC` và
`DOCUMENT_PROCESSOR_JAVA` thành đường dẫn executable tương ứng.

Bản sửa iOS Simulator đã được kiểm tra với PDF mẫu 30 trang, đọc đủ 10 QR trong
ứng dụng host. Camera trên thiết bị thật vẫn cần kiểm tra với giấy nhỏ/mờ/xoay,
chụp nhiều đợt và thao tác hủy. Không công bố tỷ lệ tăng tốc hoặc mức RAM chung
cho mọi thiết bị.

## Log Android

Log native dùng tag `DocumentScan`, mặc định tắt. Bật trên thiết bị kiểm tra:

```sh
adb shell setprop log.tag.DocumentScan DEBUG
adb logcat -v threadtime 'DocumentScan:D' 'AndroidRuntime:E' '*:S'
```

Các sự kiện gồm `camera.*`, `session.*`, `page.*`, `pdf.complete` và `barcode.*`.
Ứng dụng có thể bổ sung sự kiện qua `logDocumentEvent(event, data)`. Log có thể
chứa mã tài liệu; chỉ bật khi cần chẩn đoán. Không ghi bitmap/base64.

Tắt khi hoàn tất hoặc khởi động lại thiết bị:

```sh
adb shell setprop log.tag.DocumentScan INFO
```
