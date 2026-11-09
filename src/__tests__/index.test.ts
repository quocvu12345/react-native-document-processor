import { NativeModules } from 'react-native';

import {
  isDocumentProcessorAvailable,
  logDocumentEvent,
  processDocumentImages,
  scanBarcodes,
  scanDocument,
  scanQrImage,
} from '../index';

const native = {
  scanBarcodes: jest.fn(),
  logEvent: jest.fn(),
  scanDocument: jest.fn(),
  beginSession: jest.fn(),
  appendPage: jest.fn(),
  finishSession: jest.fn(),
  releaseSession: jest.fn(),
};

beforeEach(() => {
  jest.resetAllMocks();
  NativeModules.DocumentProcessor = native;
  native.beginSession.mockResolvedValue('session');
  native.appendPage.mockResolvedValue({
    page: 1,
    rawValues: [],
    status: 'not-detected',
  });
  native.finishSession.mockResolvedValue({
    pdfUri: 'file:///cache/document.pdf',
    directoryUri: 'file:///cache/session',
  });
  native.releaseSession.mockResolvedValue(undefined);
});

test('scans images without opening a camera or creating a document session', async () => {
  const result = {
    values: ['0030358'],
    codes: [{ value: '0030358', format: 'qr' }],
  };
  native.scanBarcodes.mockResolvedValue(result);
  expect(await scanQrImage('/photo #1%.jpg')).toBe(result);
  expect(native.scanBarcodes).toHaveBeenCalledWith(
    'file:///photo%20%231%25.jpg',
    ['qr'],
  );
  await scanBarcodes('content://photos/1', { formats: ['qr', 'ean-13', 'qr'] });
  expect(native.scanBarcodes).toHaveBeenLastCalledWith('content://photos/1', [
    'qr',
    'ean-13',
  ]);
  await scanBarcodes('file:///barcode.jpg');
  expect(native.scanBarcodes.mock.calls[2][1]).toEqual(
    expect.arrayContaining(['qr', 'ean-13', 'code-128']),
  );
  expect(native.scanDocument).not.toHaveBeenCalled();
  expect(native.beginSession).not.toHaveBeenCalled();
});

test('rejects unsupported image sources and formats before invoking native', async () => {
  for (const uri of [
    '',
    'https://example.com/image.jpg',
    'data:image/png;base64,AAAA',
    'relative.jpg',
  ]) {
    await expect(scanQrImage(uri)).rejects.toThrow('INVALID_IMAGE_URI');
  }
  await expect(scanBarcodes('/image.jpg', { formats: [] })).rejects.toThrow(
    'INVALID_BARCODE_FORMAT',
  );
  await expect(
    scanBarcodes('/image.jpg', {
      // @ts-expect-error Deliberately invalid runtime input.
      formats: ['unknown'],
    }),
  ).rejects.toThrow('INVALID_BARCODE_FORMAT');
  expect(native.scanBarcodes).not.toHaveBeenCalled();
});

test('reports native scan failures and an older binary without returning false empty results', async () => {
  native.scanBarcodes.mockRejectedValue(new Error('Unreadable image'));
  await expect(scanQrImage('/bad.jpg')).rejects.toThrow('Unreadable image');
  NativeModules.DocumentProcessor = { ...native, scanBarcodes: undefined };
  await expect(scanQrImage('/photo.jpg')).rejects.toThrow(
    'Cần cập nhật ứng dụng',
  );
});

test('diagnostic failures never interrupt scanning on older binaries or invalid payloads', () => {
  delete NativeModules.DocumentProcessor;
  expect(() => logDocumentEvent('test', {})).not.toThrow();
  NativeModules.DocumentProcessor = { ...native, logEvent: undefined };
  expect(() => logDocumentEvent('test', {})).not.toThrow();
  NativeModules.DocumentProcessor = native;
  const circular: { self?: unknown } = {};
  circular.self = circular;
  expect(() => logDocumentEvent('test', circular)).not.toThrow();
  expect(native.logEvent).not.toHaveBeenCalled();
  logDocumentEvent('test', { code: '0030358' });
  expect(native.logEvent).toHaveBeenCalledWith('test', '{"code":"0030358"}');
  native.logEvent.mockImplementation(() => {
    throw new Error('Bridge closed');
  });
  expect(() => logDocumentEvent('test', {})).not.toThrow();
});

test('processes pages sequentially and transfers completed files to the caller', async () => {
  let active = false;
  native.appendPage.mockImplementation(async (_session, uri) => {
    expect(active).toBe(false);
    active = true;
    await Promise.resolve();
    active = false;
    return {
      page: uri === 'file:///one.jpg' ? 1 : 2,
      rawValues: [],
      status: 'not-detected',
    };
  });
  const onProgress = jest.fn();
  const result = await processDocumentImages(['/one.jpg', 'content://two'], {
    onProgress,
  });
  expect(native.appendPage.mock.calls).toEqual([
    ['session', 'file:///one.jpg', true],
    ['session', 'content://two', true],
  ]);
  expect(result.pages.map(page => page.page)).toEqual([1, 2]);
  expect(onProgress.mock.calls).toEqual([
    [0, 2],
    [1, 2],
    [2, 2],
  ]);
  expect(native.releaseSession).toHaveBeenCalledTimes(1);
  expect(native.releaseSession).toHaveBeenCalledWith('session', false);
});

test('non-QR PDF creation disables native QR work', async () => {
  await processDocumentImages(['/one.jpg'], { scanQr: false });
  expect(native.appendPage).toHaveBeenCalledWith(
    'session',
    'file:///one.jpg',
    false,
  );
});

test('camera returns owned captures and preserves cancellation', async () => {
  native.scanDocument
    .mockResolvedValueOnce({
      status: 'success',
      scannedImages: ['file:///capture.jpg'],
    })
    .mockResolvedValueOnce({ status: 'cancel', scannedImages: [] });
  expect(await scanDocument()).toEqual({
    status: 'success',
    scannedImages: ['file:///capture.jpg'],
  });
  expect(native.scanDocument).toHaveBeenCalledWith({
    croppedImageQuality: 100,
  });
  expect(await scanDocument({ croppedImageQuality: 92 })).toEqual({
    status: 'cancel',
    scannedImages: [],
  });
});

test.each([-1, 101, NaN, Infinity])(
  'rejects invalid capture quality %s before starting camera',
  async quality => {
    await expect(
      scanDocument({ croppedImageQuality: quality }),
    ).rejects.toThrow('DOCUMENT_PROCESSOR_INVALID_QUALITY');
    expect(native.scanDocument).not.toHaveBeenCalled();
  },
);

test('capture errors propagate and an absent native camera requires a new binary', async () => {
  native.scanDocument.mockRejectedValue(new Error('camera unavailable'));
  await expect(scanDocument()).rejects.toThrow('camera unavailable');
  delete NativeModules.DocumentProcessor;
  await expect(scanDocument()).rejects.toThrow('Cần cập nhật ứng dụng');
});

test('cancels between pages, deleting partial PDF and all previews', async () => {
  let cancelled = false;
  await expect(
    processDocumentImages(['/one.jpg', '/two.jpg'], {
      isCancelled: () => cancelled,
      onProgress: completed => {
        cancelled = completed === 1;
      },
    }),
  ).rejects.toThrow('DOCUMENT_QR_SCAN_CANCELLED');
  expect(native.appendPage).toHaveBeenCalledTimes(1);
  expect(native.finishSession).not.toHaveBeenCalled();
  expect(native.releaseSession).toHaveBeenCalledWith('session', true);
});

test.each(['appendPage', 'finishSession'] as const)(
  'cleans session when %s fails',
  async method => {
    native[method].mockRejectedValue(new Error('disk full'));
    await expect(processDocumentImages(['/one.jpg'])).rejects.toThrow(
      'disk full',
    );
    expect(native.releaseSession).toHaveBeenCalledWith('session', true);
  },
);

test('cancellation during finalization deletes completed output instead of transferring it', async () => {
  let cancelled = false;
  native.finishSession.mockImplementation(async () => {
    cancelled = true;
    return {
      pdfUri: 'file:///cache/document.pdf',
      directoryUri: 'file:///cache/session',
    };
  });
  await expect(
    processDocumentImages(['/one.jpg'], { isCancelled: () => cancelled }),
  ).rejects.toThrow('DOCUMENT_QR_SCAN_CANCELLED');
  expect(native.releaseSession).toHaveBeenCalledWith('session', true);
});

test('cleans session if a progress callback fails', async () => {
  await expect(
    processDocumentImages(['/one.jpg'], {
      onProgress: () => {
        throw new Error('unmounted');
      },
    }),
  ).rejects.toThrow('unmounted');
  expect(native.releaseSession).toHaveBeenCalledWith('session', true);
});

test('empty inputs and unavailable native binaries fail before allocating a session', async () => {
  await expect(processDocumentImages([])).rejects.toThrow(
    'DOCUMENT_PROCESSOR_EMPTY',
  );
  delete NativeModules.DocumentProcessor;
  expect(isDocumentProcessorAvailable()).toBe(false);
  await expect(processDocumentImages(['/one.jpg'])).rejects.toThrow(
    'DOCUMENT_PROCESSOR_UNAVAILABLE',
  );
  expect(native.beginSession).not.toHaveBeenCalled();
});
