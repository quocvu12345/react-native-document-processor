#import <React/RCTBridgeModule.h>

@interface DPDocumentCapture : NSObject
- (instancetype)initWithWorker:(dispatch_queue_t)worker;
- (void)scanWithQuality:(double)quality
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject;
- (void)invalidate;
@end
