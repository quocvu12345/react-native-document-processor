# react-native-document-processor

Phiên bản **0.1.0** — thư viện React Native chụp tài liệu, tạo PDF và đọc QR/barcode
bằng native. Được phát triển cho luồng giấy tờ của 24hMoney.

Repository: [quocvu12345/react-native-document-processor](https://github.com/quocvu12345/react-native-document-processor).

## Cài từ GitHub

```sh
yarn add 'react-native-document-processor@git+https://github.com/quocvu12345/react-native-document-processor.git#v0.1.0'
pod install --project-directory=ios
```

Yêu cầu Android API 28+, iOS 15.1+ và React Native có autolinking. Đã kiểm tra
trong ứng dụng React Native 0.83. Camera Android dùng Google Play services;
ML Kit barcode model được đóng gói sẵn trong app. iOS cần khai báo
`NSCameraUsageDescription` trong `Info.plist` của ứng dụng.

Phân phối qua GitHub tag `v0.1.0`; chưa phát hành lên npm. `private: true` trong
`package.json` ngăn publish nhầm lên npm, không ngăn cài package từ GitHub.
Metadata giấy phép hiện giữ nguyên `UNLICENSED`.

## Luồng xử lý

`scanDocument` của chính module này mở camera native: VisionKit trên iOS và
ML Kit Document Scanner trên Android, có phát hiện biên, crop và xác nhận ảnh.
Đã thay thế và gỡ `react-native-document-scanner-plugin` cùng
`react-native-images-to-pdf`. Sau khi người dùng hoàn tất chụp:

```text
Ảnh đã scan
  → native decode + chuẩn hóa hướng EXIF
  → đọc QR trên bitmap
  → tạo preview nhỏ + ghi trang PDF từ cùng bitmap
  → trả PDF URI + mã QR + số trang về JS
  → đối chiếu mã giấy tờ và upload PDF bằng logic hiện có
```

Mỗi trang cần một lời gọi `appendPage`; bắt đầu, hoàn tất và giải phóng session
có ba lời gọi riêng. Không truyền bitmap/base64 qua bridge, không ghi ảnh crop
hoặc ảnh resize trung gian, không render lại PDF để đọc QR. Các trang xử lý tuần
tự trên worker riêng. Android ghi JPEG trực tiếp vào PDF và chỉ giữ metadata của
các trang đã xong; iOS dùng Core Graphics PDF context và autorelease pool từng trang.

- iOS: Vision chỉ đọc QR, ImageIO decode ảnh, Core Graphics tạo PDF.
- Android: ML Kit barcode-scanning **bundled** 17.3.0 chỉ đọc QR, ImageDecoder
  chuẩn hóa EXIF, `JpegPdfWriter` ghi PDF image-only; camera dùng
  play-services-mlkit-document-scanner 16.0.0 và chỉ yêu cầu JPEG.
- Đọc ảnh tối đa 4096 px cạnh dài trước khi encode PDF; PDF tối đa 3000 px,
  JPEG 92%; preview tối đa 1000 px, JPEG 85%. Không upscale ảnh nhỏ.
- Khi chưa thấy payload `doc|...|<số>`, thử bốn vùng chồng lấn 60% trong RAM như
  chiến lược cũ. JS vẫn là nơi parse, giữ số 0 đầu, gộp mã trùng, đối chiếu mã
  thiếu/thừa và giữ các trang xuất hiện của từng mã.
- Lỗi đọc QR được trả bằng trạng thái trang `error`; lỗi decode ảnh hoặc ghi PDF
  làm hủy cả session để tránh upload một PDF thiếu trang.
- Preview là tùy chọn; lỗi tạo preview không làm mất mã QR đã đọc được.

## Phát triển trong ứng dụng 24hMoney

Root `package.json` đã khai báo dependency `file:node_modules_dev/react-native-document-processor`.
Expo/React Native autolinking nhận cả podspec iOS và package Android.

```sh
yarn install
pod install --project-directory=ios
```

Sau đó rebuild native app bằng cấu hình development đang dùng. Reload JS/OTA
không bổ sung được native module vào binary cũ. `FileUploadHelper` luôn dùng module
mới cho camera và tạo PDF; không còn fallback sang hai thư viện đã gỡ. Cần phát
hành binary mới và quản lý runtime OTA phù hợp khi triển khai thay đổi này.

Yarn 1 có thể copy dependency `file:` vào `node_modules`; sau khi sửa source cần
đảm bảo bản dependency đã cài được cập nhật trước khi rebuild. Trong workspace
triển khai ban đầu, `node_modules/react-native-document-processor` được symlink
đến source để phát triển trực tiếp.

```ts
import { processDocumentImages, scanDocument } from 'react-native-document-processor';

const capture = await scanDocument({ croppedImageQuality: 100 });
if (capture.status === 'cancel') return;
const output = await processDocumentImages(capture.scannedImages, {
  onProgress: (completed, total) => updateProgress(completed, total),
  isCancelled: () => hasLeftScreen,
});
// output.pdfUri: PDF để upload
// output.pages: QR, trạng thái và preview của từng trang (đánh số từ 1)
// output.directoryUri: caller sở hữu sau khi thành công
// capture.scannedImages: caller cũng phải xóa sau khi dùng xong
```

Trong app, gọi `DocumentQrService.processImagesToPdf` và giải phóng
`DocumentQrService.release(qrResult)` khi hết sử dụng preview. Helper tự xóa PDF
sau upload; thư mục native chứa mọi preview được xóa khi release kết quả, kể cả
preview của trang có mã trùng. Native không xóa ảnh đầu vào. Khi hủy/lỗi trước
khi chuyển quyền sở hữu, wrapper xóa toàn bộ output của session.

Android copy JPEG từ scanner sang cache do app quản lý, không re-encode; callback
được xử lý qua activity listener duy nhất, không đăng ký launcher lặp mỗi lần scan.
iOS lưu ảnh theo từng trang trên worker và chỉ trả kết quả khi camera đã đóng.
Hủy, lỗi camera, thiếu foreground activity và một lần mở camera đang chạy đều được xử lý.

Hủy được kiểm tra giữa các trang và trước/sau khi đóng PDF. Một trang đang được
Vision/ML Kit xử lý sẽ chạy xong rồi mới dọn tài nguyên; không giải phóng bitmap
trong lúc decoder còn dùng nó.

## Phạm vi và kiểm tra

Mọi luồng `ScanFileDocument` đều dùng camera và PDF writer mới. Luồng không cần QR
truyền `scanQr: false`, bỏ qua decoder và preview nhưng vẫn trả ảnh gốc cho caller.
Luồng chọn PDF có sẵn tiếp tục dùng `react-native-pdf-thumbnail` để render trang.
`rn-qr-generator` và patch của nó đã gỡ; phần đọc mã từ ảnh trong màn QR,
`TextRecognizeService` và `DocumentQrService` đều dùng module này.
Camera quét trực tiếp vẫn dùng component VisionCamera hiện có (QR + EAN-13).
Thuật toán biên/crop tài liệu là API native của hệ điều hành.

## Đọc QR/barcode từ ảnh

```ts
import { scanBarcodes, scanQrImage } from 'react-native-document-processor';

const qr = await scanQrImage('/path/to/image.jpg'); // Chỉ QR
const all = await scanBarcodes('file:///path/to/image.jpg');
const selected = await scanBarcodes('content://provider/image', {
  formats: ['qr', 'ean-13'],
});
// { values: ['0030358'], codes: [{ value: '0030358', format: 'qr' }] }
```

Android dùng ML Kit bundled đã có; iOS dùng Vision, không thêm dependency.
API chạy trên worker, dùng chung decoder giới hạn 4096 px và chuẩn hóa EXIF với
luồng tài liệu. Không mở camera, tạo PDF, preview hoặc file tạm; không xóa ảnh đầu vào.
Hỗ trợ đường dẫn tuyệt đối/`file://`, thêm `content://` trên Android; không nhận
URL mạng/base64. Không có mã trả `values: [], codes: []`; ảnh hỏng/lỗi decoder reject.
Đọc nhiều mã, gộp mã trùng và giữ chuỗi gốc, kể cả số 0 đầu.

Các format: `qr`, `ean-13`, `ean-8`, `upc-a`, `upc-e`, `code-128`, `code-39`,
`code-93`, `codabar`, `itf`, `pdf-417`, `aztec`, `data-matrix`. iOS quy đổi UPC-A
từ EAN-13 có số 0 đầu khi yêu cầu UPC-A. Không cung cấp API tạo ảnh QR vì app
không dùng chức năng generate của thư viện cũ. Với giấy tờ, `scanQrImage` chỉ đọc QR
để tránh nhận nhầm chữ thành barcode 1D; fallback crop của luồng PDF có sẵn giữ nguyên.

Các kiểm tra tự động bao gồm matching mã, trang trùng, camera mới, xử lý tuần tự,
hủy, lỗi ghi file, cleanup và giữ ảnh của caller không cần QR. Chạy trong project
ứng dụng 24hMoney (các test service/helper nằm trong ứng dụng):

```sh
yarn jest --runInBand src/services/__tests__/DocumentQrService.test.ts src/helpers/__tests__/FileUploadHelper.documentQr.test.tsx node_modules_dev/react-native-document-processor/src/__tests__/index.test.ts

# macOS + Xcode + JDK: kiểm tra PDF bằng parser/render độc lập
bash node_modules_dev/react-native-document-processor/tests/check-pdf-writer.sh

# Tại thư mục android, có thiết bị ADB: chạy ML Kit/PDF/JNI thực
./gradlew :react-native-document-processor:connectedReleaseAndroidTest
```

Nếu clone riêng repository thư viện, kiểm tra PDF writer độc lập bằng
`bash tests/check-pdf-writer.sh` trên macOS có Xcode và JDK. Test React Native và
Android instrumentation cần chạy trong ứng dụng host đã autolink module này.

Đã kiểm tra riêng Objective-C bằng Clang với iOS SDK/React headers thực, Java bằng
javac với Android SDK và dependency đã cache. PDF Android 30 trang đã được đọc
và render bằng PDFKit, kiểm tra số trang, thứ tự, kích thước và hướng ảnh.
App Android release đã build và cài trên MI 8 Lite; bảy instrumentation test
ML Kit/PDF/cleanup/camera thiếu activity, nhiều QR, EXIF, EAN-13, Code128,
lọc QR-only và khôi phục sau ảnh lỗi đã chạy thành công trên thiết bị.
Chưa build/link toàn bộ app iOS hoặc kiểm thử chụp giấy thật tự động.
Nếu JDK không nằm trong PATH, đặt `DOCUMENT_PROCESSOR_JAVAC` và
`DOCUMENT_PROCESSOR_JAVA` trỏ đến hai executable tương ứng khi chạy script.

Cần kiểm thử thực tế bộ 10/24/30 trang, QR nhỏ/mờ/xoay, nhiều QR trên một trang,
chụp thêm 24 + 6 trang, hủy/thoát màn hình và upload lỗi. Ảnh trên 4096 px được
downsample nên cần đo khả năng đọc QR rất nhỏ trên giấy thật. Chưa đo thời gian/RAM
hoặc đưa ra tỷ lệ tăng tốc trên thiết bị.

## Log khi test Android

Log native và các sự kiện `DocumentQrService` dùng chung tag `DocumentScan`,
hoạt động cả trong release. Mặc định tắt; bật trên thiết bị test bằng:

```sh
adb shell setprop log.tag.DocumentScan DEBUG
adb logcat -v threadtime 'DocumentScan:D' 'AndroidRuntime:E' '*:S'
```

Android Studio: mở project `android`, chọn MI 8 Lite trong Logcat và lọc
`tag:DocumentScan`. Các sự kiện gồm `camera.*`, `session.*`, `page.*`,
`pdf.complete`, `barcode.*`, `js.native:complete`, `js.upload:*`. Log hiển thị thời gian
xử lý, số trang, kích thước PDF, QR từng trang, mã khớp/thiếu/thừa và lỗi.
Không ghi bitmap/base64. Log có mã giấy tờ khi bật nên chỉ lưu cục bộ để test.

Tắt khi hoàn tất (hoặc thiết bị khởi động lại):

```sh
adb shell setprop log.tag.DocumentScan INFO
```

Tài liệu API:
[Apple Vision](https://developer.apple.com/documentation/vision/vndetectbarcodesrequest),
[ML Kit barcode scanning](https://developers.google.com/ml-kit/vision/barcode-scanning/android),
[Android ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder).
