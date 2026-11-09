#!/usr/bin/env bash
set -euo pipefail

# Requires macOS/Xcode and a desktop JDK. Does not build or modify the app.
processor_root="$(cd "$(dirname "$0")/.." && pwd)"
processor_tmp="$(mktemp -d -t document-processor-test)"
trap 'rm -rf "$processor_tmp"' EXIT

"${DOCUMENT_PROCESSOR_JAVAC:-javac}" -d "$processor_tmp/classes" \
  "$processor_root/android/src/main/java/vn/money24h/documentprocessor/JpegPdfWriter.java" \
  "$processor_root/tests/JpegPdfWriterSmoke.java"
"${DOCUMENT_PROCESSOR_JAVA:-java}" -Djava.awt.headless=true -cp "$processor_tmp/classes" \
  vn.money24h.documentprocessor.JpegPdfWriterSmoke "$processor_tmp"
xcrun swift "$processor_root/tests/verify-pdf.swift" "$processor_tmp/smoke.pdf"
