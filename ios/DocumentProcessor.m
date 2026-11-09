#import <React/RCTBridgeModule.h>
#import <React/RCTInvalidating.h>
#import <ImageIO/ImageIO.h>
#import <UIKit/UIKit.h>
#import <Vision/Vision.h>
#import "DPDocumentCapture.h"

static NSError *DPError(NSString *message) {
  return [NSError errorWithDomain:@"DocumentProcessor" code:1
                        userInfo:@{NSLocalizedDescriptionKey: message}];
}

static UIImage *DPDecodeImage(NSString *uri) {
  NSURL *url = [uri hasPrefix:@"/"] ? [NSURL fileURLWithPath:uri] : [NSURL URLWithString:uri];
  if (!url.isFileURL) { return nil; }
  CGImageSourceRef source = CGImageSourceCreateWithURL((__bridge CFURLRef)url, NULL);
  NSDictionary *options = @{
    (id)kCGImageSourceCreateThumbnailFromImageAlways: @YES,
    (id)kCGImageSourceCreateThumbnailWithTransform: @YES,
    (id)kCGImageSourceThumbnailMaxPixelSize: @4096,
    (id)kCGImageSourceShouldCacheImmediately: @YES
  };
  // Bound memory and normalize EXIF, shared by image scanning and document pages.
  CGImageRef bitmap = source ? CGImageSourceCreateThumbnailAtIndex(source, 0,
      (__bridge CFDictionaryRef)options) : NULL;
  if (source) { CFRelease(source); }
  if (!bitmap) { return nil; }
  UIImage *image = [UIImage imageWithCGImage:bitmap];
  CGImageRelease(bitmap);
  return image;
}

static NSDictionary<NSString *, NSArray<VNBarcodeSymbology> *> *DPBarcodeFormats(void) {
  return @{
    @"qr": @[VNBarcodeSymbologyQR],
    @"ean-13": @[VNBarcodeSymbologyEAN13],
    @"ean-8": @[VNBarcodeSymbologyEAN8],
    // Vision reports UPC-A as EAN-13 with a leading zero.
    @"upc-a": @[VNBarcodeSymbologyEAN13],
    @"upc-e": @[VNBarcodeSymbologyUPCE],
    @"code-128": @[VNBarcodeSymbologyCode128],
    @"code-39": @[VNBarcodeSymbologyCode39, VNBarcodeSymbologyCode39Checksum,
                  VNBarcodeSymbologyCode39FullASCII, VNBarcodeSymbologyCode39FullASCIIChecksum],
    @"code-93": @[VNBarcodeSymbologyCode93, VNBarcodeSymbologyCode93i],
    @"codabar": @[VNBarcodeSymbologyCodabar],
    @"itf": @[VNBarcodeSymbologyI2of5, VNBarcodeSymbologyI2of5Checksum, VNBarcodeSymbologyITF14],
    @"pdf-417": @[VNBarcodeSymbologyPDF417],
    @"aztec": @[VNBarcodeSymbologyAztec],
    @"data-matrix": @[VNBarcodeSymbologyDataMatrix]
  };
}

static NSArray<VNBarcodeObservation *> *DPReadBarcodes(CGImageRef image,
    NSArray<VNBarcodeSymbology> *symbologies, NSError **error) {
  VNDetectBarcodesRequest *request = [VNDetectBarcodesRequest new];
  request.symbologies = symbologies;
  VNImageRequestHandler *handler = [[VNImageRequestHandler alloc] initWithCGImage:image options:@{}];
  return [handler performRequests:@[request] error:error] ? (request.results ?: @[]) : nil;
}

static BOOL DPHasDocument(NSArray<NSString *> *values) {
  for (NSString *value in values) {
    if ([value rangeOfString:@"^doc\\|[^|]+\\|[0-9]+$"
                    options:NSRegularExpressionSearch].location != NSNotFound) {
      return YES;
    }
  }
  return NO;
}

static UIImage *DPResize(UIImage *image, CGFloat maxDimension) {
  CGFloat scale = MIN(1, maxDimension / MAX(image.size.width, image.size.height));
  CGSize size = CGSizeMake(MAX(1, round(image.size.width * scale)),
                           MAX(1, round(image.size.height * scale)));
  UIGraphicsImageRendererFormat *format = [UIGraphicsImageRendererFormat defaultFormat];
  format.scale = 1;
  format.opaque = YES;
  UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:size format:format];
  return [renderer imageWithActions:^(UIGraphicsImageRendererContext *context) {
    [[UIColor whiteColor] setFill];
    UIRectFill((CGRect){CGPointZero, size});
    [image drawInRect:(CGRect){CGPointZero, size}];
  }];
}

static BOOL DPReadQR(CGImageRef image, NSMutableOrderedSet<NSString *> *values) {
  NSError *error = nil;
  NSArray<VNBarcodeObservation *> *observations = DPReadBarcodes(image, @[VNBarcodeSymbologyQR], &error);
  if (!observations) { return NO; }
  for (VNBarcodeObservation *observation in observations) {
    if (observation.payloadStringValue.length) {
      [values addObject:observation.payloadStringValue];
    }
  }
  return YES;
}

@interface DPSession : NSObject
@property(nonatomic, strong) NSURL *directory;
@property(nonatomic, strong) NSURL *pdfURL;
@property(nonatomic, assign) CGContextRef pdf;
@property(nonatomic, assign) NSUInteger pageCount;
- (void)close;
@end

@implementation DPSession
- (void)close {
  if (_pdf) {
    CGPDFContextClose(_pdf);
    CGContextRelease(_pdf);
    _pdf = NULL;
  }
}
- (void)dealloc { [self close]; }
@end

@interface DocumentProcessor : NSObject <RCTBridgeModule, RCTInvalidating>
@end

@implementation DocumentProcessor {
  dispatch_queue_t _worker;
  NSMutableDictionary<NSString *, DPSession *> *_sessions;
  DPDocumentCapture *_capture;
}

RCT_EXPORT_MODULE(DocumentProcessor)

+ (BOOL)requiresMainQueueSetup { return NO; }

- (instancetype)init {
  if ((self = [super init])) {
    _worker = dispatch_queue_create("vn.money24h.documentprocessor", DISPATCH_QUEUE_SERIAL);
    _sessions = [NSMutableDictionary new];
    _capture = [[DPDocumentCapture alloc] initWithWorker:_worker];
  }
  return self;
}

- (dispatch_queue_t)methodQueue { return _worker; }

RCT_EXPORT_METHOD(scanDocument:(NSDictionary *)options
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  [_capture scanWithQuality:options[@"croppedImageQuality"] ? [options[@"croppedImageQuality"] doubleValue] : 100
                   resolve:resolve reject:reject];
}

RCT_EXPORT_METHOD(scanBarcodes:(NSString *)uri
                  formats:(NSArray<NSString *> *)formats
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  @autoreleasepool {
    NSDictionary *mapping = DPBarcodeFormats();
    NSMutableOrderedSet<VNBarcodeSymbology> *symbologies = [NSMutableOrderedSet new];
    for (NSString *format in formats) {
      if (!mapping[format]) {
        reject(@"DOCUMENT_PROCESSOR_BARCODE_FORMAT", @"Unsupported barcode format.", nil);
        return;
      }
      [symbologies addObjectsFromArray:mapping[format]];
    }
    if (!symbologies.count) {
      reject(@"DOCUMENT_PROCESSOR_BARCODE_FORMAT", @"At least one barcode format is required.", nil);
      return;
    }
    UIImage *image = DPDecodeImage(uri);
    if (!image) {
      reject(@"DOCUMENT_PROCESSOR_IMAGE", @"Cannot decode local image.", nil);
      return;
    }
    NSError *error = nil;
    NSArray<VNBarcodeObservation *> *observations = DPReadBarcodes(image.CGImage, symbologies.array, &error);
    if (!observations) {
      reject(@"DOCUMENT_PROCESSOR_BARCODE_SCAN", @"Cannot scan image codes.", error);
      return;
    }
    NSMutableOrderedSet<NSString *> *values = [NSMutableOrderedSet new];
    NSMutableOrderedSet<NSDictionary *> *codes = [NSMutableOrderedSet new];
    for (VNBarcodeObservation *observation in observations) {
      NSString *value = observation.payloadStringValue;
      if (!value.length) { continue; }
      NSString *detectedFormat = nil;
      if ([observation.symbology isEqualToString:VNBarcodeSymbologyEAN13]) {
        if (value.length == 13 && [value hasPrefix:@"0"] && [formats containsObject:@"upc-a"]) {
          detectedFormat = @"upc-a";
          value = [value substringFromIndex:1];
        } else if ([formats containsObject:@"ean-13"]) {
          detectedFormat = @"ean-13";
        }
      } else {
        for (NSString *format in formats) {
          if ([mapping[format] containsObject:observation.symbology]) {
            detectedFormat = format;
            break;
          }
        }
      }
      if (detectedFormat) {
        [values addObject:value];
        [codes addObject:@{@"value": value, @"format": detectedFormat}];
      }
    }
    resolve(@{@"values": values.array, @"codes": codes.array});
  }
}

RCT_EXPORT_METHOD(beginSession:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  NSString *identifier = [NSUUID UUID].UUIDString;
  NSURL *cache = [[NSFileManager defaultManager] URLsForDirectory:NSCachesDirectory
                                                      inDomains:NSUserDomainMask].firstObject;
  DPSession *session = [DPSession new];
  session.directory = [cache URLByAppendingPathComponent:
      [@"document-processor-" stringByAppendingString:identifier] isDirectory:YES];
  session.pdfURL = [session.directory URLByAppendingPathComponent:@"document.pdf"];
  NSError *error = nil;
  if (!cache || ![[NSFileManager defaultManager] createDirectoryAtURL:session.directory
                                        withIntermediateDirectories:YES attributes:nil error:&error]) {
    reject(@"DOCUMENT_PROCESSOR_CREATE", @"Cannot create document cache.", error);
    return;
  }
  session.pdf = CGPDFContextCreateWithURL((__bridge CFURLRef)session.pdfURL, NULL, NULL);
  if (!session.pdf) {
    [[NSFileManager defaultManager] removeItemAtURL:session.directory error:nil];
    reject(@"DOCUMENT_PROCESSOR_CREATE", @"Cannot create PDF.", nil);
    return;
  }
  _sessions[identifier] = session;
  resolve(identifier);
}

RCT_EXPORT_METHOD(appendPage:(NSString *)identifier
                  uri:(NSString *)uri
                  scanQr:(BOOL)scanQr
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  @autoreleasepool {
    DPSession *session = _sessions[identifier];
    if (!session.pdf) {
      reject(@"DOCUMENT_PROCESSOR_SESSION", @"Session is closed or missing.", nil);
      return;
    }
    UIImage *image = DPDecodeImage(uri);
    if (!image) {
      reject(@"DOCUMENT_PROCESSOR_IMAGE", @"Cannot decode document image.", nil);
      return;
    }
    NSMutableOrderedSet<NSString *> *values = [NSMutableOrderedSet new];
    BOOL hasError = scanQr && !DPReadQR(image.CGImage, values);
    if (scanQr && !DPHasDocument(values.array)) {
      size_t width = CGImageGetWidth(image.CGImage);
      size_t height = CGImageGetHeight(image.CGImage);
      size_t cropWidth = (size_t)ceil(width * 0.6);
      size_t cropHeight = (size_t)ceil(height * 0.6);
      CGPoint offsets[] = {{width - cropWidth, 0}, {0, 0},
          {width - cropWidth, height - cropHeight}, {0, height - cropHeight}};
      for (int index = 0; index < 4; index++) {
        @autoreleasepool {
          CGImageRef crop = CGImageCreateWithImageInRect(image.CGImage,
              (CGRect){offsets[index], CGSizeMake(cropWidth, cropHeight)});
          if (!crop || !DPReadQR(crop, values)) { hasError = YES; }
          if (crop) { CGImageRelease(crop); }
        }
      }
    }

    NSMutableDictionary *page = [@{
      @"page": @(session.pageCount + 1),
      @"rawValues": values.array,
      @"status": hasError ? @"error" : (values.count ? @"decoded" : @"not-detected")
    } mutableCopy];
    // Optional previews must never discard decoded QR data on write failure.
    if (DPHasDocument(values.array)) {
      @autoreleasepool {
        NSData *preview = UIImageJPEGRepresentation(DPResize(image, 1000), 0.85);
        NSURL *previewURL = [session.directory URLByAppendingPathComponent:
            [NSString stringWithFormat:@"page-%lu.jpg", (unsigned long)session.pageCount + 1]];
        if ([preview writeToURL:previewURL options:NSDataWritingAtomic error:nil]) {
          page[@"previewUri"] = previewURL.absoluteString;
        }
      }
    }

    // Feed compressed JPEG data to Core Graphics, without a resized image file.
    NSData *jpeg = UIImageJPEGRepresentation(DPResize(image, 3000), 0.92);
    CGDataProviderRef provider = jpeg ? CGDataProviderCreateWithCFData((__bridge CFDataRef)jpeg) : NULL;
    CGImageRef pdfImage = provider ? CGImageCreateWithJPEGDataProvider(provider, NULL, NO,
        kCGRenderingIntentDefault) : NULL;
    if (provider) { CGDataProviderRelease(provider); }
    if (!pdfImage) {
      reject(@"DOCUMENT_PROCESSOR_PDF", @"Cannot encode PDF page.", DPError(@"JPEG encoding failed."));
      return;
    }
    CGRect bounds = CGRectMake(0, 0, CGImageGetWidth(pdfImage) * 72.0 / 150.0,
                                    CGImageGetHeight(pdfImage) * 72.0 / 150.0);
    NSData *mediaBox = [NSData dataWithBytes:&bounds length:sizeof(bounds)];
    CGPDFContextBeginPage(session.pdf, (__bridge CFDictionaryRef)@{(id)kCGPDFContextMediaBox: mediaBox});
    CGContextDrawImage(session.pdf, bounds, pdfImage);
    CGPDFContextEndPage(session.pdf);
    CGImageRelease(pdfImage);
    session.pageCount++;
    resolve(page);
  }
}

RCT_EXPORT_METHOD(finishSession:(NSString *)identifier
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  DPSession *session = _sessions[identifier];
  if (!session.pdf || !session.pageCount) {
    reject(@"DOCUMENT_PROCESSOR_SESSION", @"Session is empty or closed.", nil);
    return;
  }
  [session close];
  CGPDFDocumentRef document = CGPDFDocumentCreateWithURL((__bridge CFURLRef)session.pdfURL);
  BOOL valid = document && CGPDFDocumentGetNumberOfPages(document) == session.pageCount;
  if (document) { CGPDFDocumentRelease(document); }
  if (!valid) {
    reject(@"DOCUMENT_PROCESSOR_PDF", @"Cannot finalize PDF.", nil);
    return;
  }
  resolve(@{@"pdfUri": session.pdfURL.absoluteString, @"directoryUri": session.directory.absoluteString});
}

RCT_EXPORT_METHOD(releaseSession:(NSString *)identifier
                  deleteFiles:(BOOL)deleteFiles
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
  DPSession *session = _sessions[identifier];
  [session close];
  if (deleteFiles && session) {
    [[NSFileManager defaultManager] removeItemAtURL:session.directory error:nil];
  }
  [_sessions removeObjectForKey:identifier];
  resolve(nil);
}

- (void)invalidate {
  [_capture invalidate];
  dispatch_async(_worker, ^{
    for (DPSession *session in self->_sessions.allValues) {
      [session close];
      [[NSFileManager defaultManager] removeItemAtURL:session.directory error:nil];
    }
    [self->_sessions removeAllObjects];
  });
}
@end
