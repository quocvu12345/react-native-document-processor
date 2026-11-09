#import "DPDocumentCapture.h"
#import <React/RCTUtils.h>
#import <VisionKit/VisionKit.h>

@interface DPDocumentCapture () <VNDocumentCameraViewControllerDelegate>
@property(atomic, assign) BOOL invalidated;
@end

@implementation DPDocumentCapture {
  dispatch_queue_t _worker;
  VNDocumentCameraViewController *_controller;
  RCTPromiseResolveBlock _resolve;
  RCTPromiseRejectBlock _reject;
  double _quality;
}

- (instancetype)initWithWorker:(dispatch_queue_t)worker {
  if ((self = [super init])) { _worker = worker; }
  return self;
}

// All camera/promise state is accessed on the main queue.
- (void)scanWithQuality:(double)quality
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
  dispatch_async(dispatch_get_main_queue(), ^{
    if (self.invalidated || self->_resolve) {
      reject(@"DOCUMENT_CAPTURE_BUSY", @"Document camera is busy or closed.", nil);
      return;
    }
    if (!isfinite(quality) || quality < 0 || quality > 100) {
      reject(@"DOCUMENT_CAPTURE_QUALITY", @"Invalid JPEG quality.", nil);
      return;
    }
    UIViewController *presenter = RCTPresentedViewController();
    if (!VNDocumentCameraViewController.isSupported || !presenter || !presenter.view.window) {
      reject(@"DOCUMENT_CAPTURE_UNAVAILABLE", @"Document camera is unavailable on this device.", nil);
      return;
    }
    self->_resolve = resolve;
    self->_reject = reject;
    self->_quality = quality / 100.0;
    self->_controller = [VNDocumentCameraViewController new];
    self->_controller.delegate = self;
    [presenter presentViewController:self->_controller animated:YES completion:nil];
  });
}

- (void)clear {
  _controller.delegate = nil;
  _controller = nil;
  _resolve = nil;
  _reject = nil;
}

- (void)documentCameraViewControllerDidCancel:(VNDocumentCameraViewController *)controller {
  [controller dismissViewControllerAnimated:YES completion:^{
    RCTPromiseResolveBlock resolve = self->_resolve;
    [self clear];
    if (resolve) { resolve(@{@"status": @"cancel", @"scannedImages": @[]}); }
  }];
}

- (void)documentCameraViewController:(VNDocumentCameraViewController *)controller
                   didFailWithError:(NSError *)error {
  [controller dismissViewControllerAnimated:YES completion:^{
    RCTPromiseRejectBlock reject = self->_reject;
    [self clear];
    if (reject) { reject(@"DOCUMENT_CAPTURE_ERROR", error.localizedDescription, error); }
  }];
}

- (void)documentCameraViewController:(VNDocumentCameraViewController *)controller
                  didFinishWithScan:(VNDocumentCameraScan *)scan {
  double quality = _quality;
  [controller dismissViewControllerAnimated:YES completion:^{
    dispatch_async(self->_worker, ^{
      NSMutableArray<NSString *> *paths = [NSMutableArray new];
      NSError *error = nil;
      NSURL *cache = [[NSFileManager defaultManager] URLsForDirectory:NSCachesDirectory
                                                          inDomains:NSUserDomainMask].firstObject;
      for (NSUInteger index = 0; index < scan.pageCount && !self.invalidated; index++) {
        @autoreleasepool {
          NSURL *url = [cache URLByAppendingPathComponent:
              [NSString stringWithFormat:@"document-capture-%@.jpg", NSUUID.UUID.UUIDString]];
          NSData *jpeg = UIImageJPEGRepresentation([scan imageOfPageAtIndex:index], quality);
          if (!jpeg || !url) {
            error = [NSError errorWithDomain:@"DocumentCapture" code:1 userInfo:
                @{NSLocalizedDescriptionKey: @"Cannot encode scanned page."}];
            break;
          }
          [paths addObject:url.absoluteString];
          if (![jpeg writeToURL:url options:NSDataWritingAtomic error:&error]) { break; }
        }
      }
      if (!scan.pageCount && !error) {
        error = [NSError errorWithDomain:@"DocumentCapture" code:2 userInfo:
            @{NSLocalizedDescriptionKey: @"Document camera returned no pages."}];
      }
      dispatch_async(dispatch_get_main_queue(), ^{
        RCTPromiseResolveBlock resolve = self->_resolve;
        RCTPromiseRejectBlock reject = self->_reject;
        [self clear];
        if (error || self.invalidated || !resolve) {
          dispatch_async(self->_worker, ^{
            for (NSString *path in paths) {
              [[NSFileManager defaultManager] removeItemAtURL:[NSURL URLWithString:path] error:nil];
            }
          });
          if (reject) { reject(@"DOCUMENT_CAPTURE_ERROR", error.localizedDescription, error); }
        } else {
          resolve(@{@"status": @"success", @"scannedImages": paths});
        }
      });
    });
  }];
}

- (void)invalidate {
  self.invalidated = YES;
  dispatch_async(dispatch_get_main_queue(), ^{
    [self->_controller dismissViewControllerAnimated:NO completion:nil];
    RCTPromiseRejectBlock reject = self->_reject;
    [self clear];
    if (reject) { reject(@"DOCUMENT_CAPTURE_CANCELLED", @"Document camera was closed.", nil); }
  });
}
@end
