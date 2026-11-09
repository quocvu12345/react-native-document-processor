import AppKit
import Foundation
import PDFKit

let document = PDFDocument(url: URL(fileURLWithPath: CommandLine.arguments[1]))!
precondition(document.pageCount == 30)
for index in 0..<30 {
    let page = document.page(at: index)!
    let bounds = page.bounds(for: .mediaBox)
    let landscape = index % 2 == 0
    precondition(abs(bounds.width - (landscape ? 144 : 96)) < 0.01)
    precondition(abs(bounds.height - (landscape ? 96 : 144)) < 0.01)
    let image = page.thumbnail(of: NSSize(width: 300, height: 300), for: .mediaBox)
    let bitmap = NSBitmapImageRep(data: image.tiffRepresentation!)!
    let top = bitmap.colorAt(x: bitmap.pixelsWide / 2, y: bitmap.pixelsHigh / 4)!
        .usingColorSpace(.deviceRGB)!
    let bottom = bitmap.colorAt(x: bitmap.pixelsWide / 2, y: bitmap.pixelsHigh * 3 / 4)!
        .usingColorSpace(.deviceRGB)!
    precondition(bottom.blueComponent > 0.8 && bottom.redComponent < 0.2,
                 "Inverted image at page \(index + 1)")
    precondition(landscape ? top.redComponent > 0.8 : top.greenComponent > 0.8,
                 "Incorrect page order/color at page \(index + 1)")
}
print("PDFKit: 30 pages rendered; dimensions, order and orientation passed.")
