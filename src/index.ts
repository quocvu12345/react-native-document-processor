import { NativeModules } from 'react-native';

export type ProcessedDocumentPage = {
  page: number;
  rawValues: string[];
  status: 'decoded' | 'not-detected' | 'error';
  previewUri?: string;
};

export type ProcessedDocument = {
  pdfUri: string;
  /** Caller owns this directory after success, including PDF and previews. */
  directoryUri: string;
  pages: ProcessedDocumentPage[];
};

type ProcessorModule = {
  scanBarcodes(
    uri: string,
    formats: readonly ImageBarcodeFormat[],
  ): Promise<ImageBarcodeResult>;
  scanDocument(options: ScanDocumentOptions): Promise<DocumentCapture>;
  beginSession(): Promise<string>;
  appendPage(
    session: string,
    uri: string,
    scanQr: boolean,
  ): Promise<ProcessedDocumentPage>;
  finishSession(session: string): Promise<Omit<ProcessedDocument, 'pages'>>;
  releaseSession(session: string, deleteFiles: boolean): Promise<void>;
  /** Android diagnostics, enabled with log.tag.DocumentScan=DEBUG. */
  logEvent?(event: string, details: string): void;
};

type ProcessOptions = {
  scanQr?: boolean;
  onProgress?: (completed: number, total: number) => void;
  isCancelled?: () => boolean;
};

export type ScanDocumentOptions = {
  /** iOS JPEG quality, 0–100. Android returns the scanner's original JPEG. */
  croppedImageQuality?: number;
};

export type DocumentCapture = {
  status: 'success' | 'cancel';
  /** Caller owns these local files and must remove them after use. */
  scannedImages: string[];
};

export async function scanDocument(
  options: ScanDocumentOptions = {},
): Promise<DocumentCapture> {
  const native = getModule();
  if (typeof native?.scanDocument !== 'function') {
    throw new Error('Cần cập nhật ứng dụng để quét tài liệu.');
  }
  const quality = options.croppedImageQuality ?? 100;
  if (!Number.isFinite(quality) || quality < 0 || quality > 100) {
    throw new Error('DOCUMENT_PROCESSOR_INVALID_QUALITY');
  }
  return native.scanDocument({ croppedImageQuality: quality });
}

const getModule = (): ProcessorModule | undefined =>
  NativeModules.DocumentProcessor;

const localImageUri = (path: string) =>
  path.startsWith('/')
    ? `file://${path.split('/').map(encodeURIComponent).join('/')}`
    : path;

const BARCODE_FORMATS = [
  'qr',
  'ean-13',
  'ean-8',
  'upc-a',
  'upc-e',
  'code-128',
  'code-39',
  'code-93',
  'codabar',
  'itf',
  'pdf-417',
  'aztec',
  'data-matrix',
] as const;

export type ImageBarcodeFormat = (typeof BARCODE_FORMATS)[number];
export type ImageBarcodeResult = {
  /** Unique raw payloads; leading zeroes and whitespace are preserved. */
  values: string[];
  codes: { value: string; format: ImageBarcodeFormat }[];
};

/** Decode a local image without creating a PDF, preview, or temporary image. */
export async function scanBarcodes(
  uri: string,
  options: { formats?: readonly ImageBarcodeFormat[] } = {},
): Promise<ImageBarcodeResult> {
  const native = getModule();
  if (typeof native?.scanBarcodes !== 'function') {
    throw new Error('Cần cập nhật ứng dụng để quét mã từ ảnh.');
  }
  if (typeof uri !== 'string' || !/^(\/|file:\/\/|content:\/\/)/.test(uri)) {
    throw new Error('DOCUMENT_PROCESSOR_INVALID_IMAGE_URI');
  }
  const formats = options.formats ?? BARCODE_FORMATS;
  if (
    !formats.length ||
    formats.some(format => !BARCODE_FORMATS.includes(format))
  ) {
    throw new Error('DOCUMENT_PROCESSOR_INVALID_BARCODE_FORMAT');
  }
  return native.scanBarcodes(localImageUri(uri), [...new Set(formats)]);
}

/** QR only: ignore barcode-like text/1D codes on document pages. */
export const scanQrImage = (uri: string): Promise<ImageBarcodeResult> =>
  scanBarcodes(uri, { formats: ['qr'] });

/** Diagnostics must never interrupt a scan, including on older binaries/iOS. */
export const logDocumentEvent = (event: string, data: unknown): void => {
  try {
    const native = getModule();
    if (typeof native?.logEvent === 'function') {
      native.logEvent(event, JSON.stringify(data) ?? 'null');
    }
  } catch {
    // A diagnostic payload or unavailable bridge must not fail the document flow.
  }
};

export const isDocumentProcessorAvailable = () => {
  const native = getModule();
  return (
    typeof native?.beginSession === 'function' &&
    typeof native.appendPage === 'function' &&
    typeof native.finishSession === 'function' &&
    typeof native.releaseSession === 'function'
  );
};

/** One native call per page: decode, QR, preview, append to PDF. No base64. */
export async function processDocumentImages(
  paths: readonly string[],
  options: ProcessOptions = {},
): Promise<ProcessedDocument> {
  const native = getModule();
  if (!native || !isDocumentProcessorAvailable()) {
    throw new Error('DOCUMENT_PROCESSOR_UNAVAILABLE');
  }
  if (!paths.length) {
    throw new Error('DOCUMENT_PROCESSOR_EMPTY');
  }
  const assertActive = () => {
    if (options.isCancelled?.()) {
      throw new Error('DOCUMENT_QR_SCAN_CANCELLED');
    }
  };
  assertActive();
  const session = await native.beginSession();
  let transferred = false;
  try {
    const pages: ProcessedDocumentPage[] = [];
    options.onProgress?.(0, paths.length);
    for (const path of paths) {
      assertActive();
      pages.push(
        await native.appendPage(
          session,
          localImageUri(path),
          options.scanQr !== false,
        ),
      );
      assertActive();
      options.onProgress?.(pages.length, paths.length);
    }
    assertActive();
    const output = await native.finishSession(session);
    assertActive();
    // Transfer file ownership only after PDF finalization and cancellation checks.
    await native.releaseSession(session, false);
    transferred = true;
    return { ...output, pages };
  } finally {
    if (!transferred) {
      await native.releaseSession(session, true);
    }
  }
}
