// macOS-only synthetic test fixtures. No personal images or network input.
// ImageIO API: https://developer.apple.com/documentation/imageio/cgimagedestination
import AppKit
import ImageIO
import UniformTypeIdentifiers

struct Fixture {
    let id: String
    let title: String
    let time: String?
    let latitude: Double?
    let longitude: Double?
}

let fixtures = [
    Fixture(id: "p1", title: "식당 · 합성 시연 사진", time: "12:25:00", latitude: 37.2831, longitude: 126.5811),
    Fixture(id: "p2", title: "해변 1 · 합성 시연 사진", time: "13:45:00", latitude: 37.2846, longitude: 126.5779),
    Fixture(id: "p3", title: "해변 2 · 합성 시연 사진", time: "14:10:00", latitude: 37.2846, longitude: 126.5779),
    Fixture(id: "p4", title: "해변 3 · 합성 시연 사진", time: "14:30:00", latitude: 37.2846, longitude: 126.5779),
    Fixture(id: "p5", title: "카페 · 합성 시연 사진", time: "15:20:00", latitude: 37.2790, longitude: 126.5850),
    Fixture(id: "p6", title: "영수증 대역 · 실제 결제정보 없음", time: "14:12:00", latitude: 37.2846, longitude: 126.5779),
    Fixture(id: "p7", title: "시각·위치 없음 · 미연결 확인", time: nil, latitude: nil, longitude: nil),
]

guard CommandLine.arguments.count == 2 else {
    fputs("Usage: swift scripts/generate_photo_fixtures.swift OUTPUT_DIRECTORY\n", stderr)
    exit(2)
}
let output = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let files = fixtures.map { output.appendingPathComponent("pleos-stage4-\($0.id).jpg") }
guard files.allSatisfy({ !FileManager.default.fileExists(atPath: $0.path) }) else {
    fputs("Refusing to overwrite an existing fixture; choose another output directory.\n", stderr)
    exit(2)
}
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
for (index, fixture) in fixtures.enumerated() {
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 960, pixelsHigh: 640,
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    guard let graphics = NSGraphicsContext(bitmapImageRep: bitmap) else {
        fatalError("Could not create bitmap drawing context")
    }
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = graphics
    NSColor(calibratedHue: CGFloat(index) / 8, saturation: 0.38, brightness: 0.9, alpha: 1).setFill()
    NSRect(x: 0, y: 0, width: 960, height: 640).fill()
    let titleStyle: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 42, weight: .bold), .foregroundColor: NSColor.black]
    let bodyStyle: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 30), .foregroundColor: NSColor.black]
    (fixture.title as NSString).draw(in: NSRect(x: 48, y: 400, width: 870, height: 120), withAttributes: titleStyle)
    ("\(fixture.id)  |  2026-11-08  \(fixture.time ?? "--:--")\n실제 촬영·개인 정보가 없는 시험 이미지" as NSString)
        .draw(in: NSRect(x: 48, y: 240, width: 860, height: 130), withAttributes: bodyStyle)
    NSGraphicsContext.restoreGraphicsState()
    var properties: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: 0.9]
    if let time = fixture.time {
        // Offset omitted intentionally: production must use Asia/Seoul fallback.
        properties[kCGImagePropertyExifDictionary] = [kCGImagePropertyExifDateTimeOriginal: "2026:11:08 \(time)"]
    }
    if let latitude = fixture.latitude, let longitude = fixture.longitude {
        properties[kCGImagePropertyGPSDictionary] = [
            kCGImagePropertyGPSLatitude: latitude, kCGImagePropertyGPSLatitudeRef: "N",
            kCGImagePropertyGPSLongitude: longitude, kCGImagePropertyGPSLongitudeRef: "E",
        ]
    }
    guard let image = bitmap.cgImage,
          let destination = CGImageDestinationCreateWithURL(files[index] as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
        fatalError("Could not create synthetic image")
    }
    CGImageDestinationAddImage(destination, image, properties as CFDictionary)
    guard CGImageDestinationFinalize(destination),
          let source = CGImageSourceCreateWithURL(files[index] as CFURL, nil),
          let decoded = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
        fatalError("Could not verify generated fixture")
    }
    let exif = decoded[kCGImagePropertyExifDictionary] as? [CFString: Any]
    let gps = decoded[kCGImagePropertyGPSDictionary] as? [CFString: Any]
    precondition((exif?[kCGImagePropertyExifDateTimeOriginal] as? String) == fixture.time.map { "2026:11:08 \($0)" })
    precondition((gps?[kCGImagePropertyGPSLatitude] as? Double) == fixture.latitude)
    precondition((gps?[kCGImagePropertyGPSLongitude] as? Double) == fixture.longitude)
    print("Generated and verified \(files[index].lastPathComponent)")
}
