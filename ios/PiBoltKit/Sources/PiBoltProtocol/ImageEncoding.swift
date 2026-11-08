import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Sizes and limits of images sent to Pi, as the Android app's `ImageSizing` (ui/session/Attachments.kt).
public enum ImageSizing {
    /// Images per message.
    public static let maxImages = 4
    /// The long edge sent: models downscale larger images anyway, and it keeps a prompt small.
    public static let maxEdge = 2000
    /// Pi refuses an image whose base64 is larger (4.5 MiB).
    public static let maxBase64 = 4_718_592
    /// A transparent PNG is kept as PNG only up to this many bytes; larger ones go to JPEG.
    public static let pngKeepBytes = 1_500_000
    public static let jpegQuality = 0.85
    /// The composer's thumbnails.
    public static let thumbEdge = 192

    /// `width` x `height` scaled so the long edge is at most `maxEdge`; never upscaled, never below 1 px.
    public static func target(_ width: Int, _ height: Int, maxEdge: Int = maxEdge) -> (Int, Int) {
        let long = max(width, height)
        if long <= maxEdge || long <= 0 { return (max(width, 1), max(height, 1)) }
        let scale = Double(maxEdge) / Double(long)
        return (max(Int((Double(width) * scale).rounded()), 1), max(Int((Double(height) * scale).rounded()), 1))
    }

    public static func base64Size(_ bytes: Int) -> Int { (bytes + 2) / 3 * 4 }

    public static func fits(_ bytes: Int) -> Bool { base64Size(bytes) <= maxBase64 }

    /// PNG only for images with transparency whose PNG stays small; everything else is JPEG.
    public static func keepPng(hasAlpha: Bool, pngBytes: Int) -> Bool { hasAlpha && pngBytes <= pngKeepBytes }

    public static func slotsLeft(_ attached: Int) -> Int { max(maxImages - attached, 0) }
}

public enum ImageEncodingError: Error, Equatable, CustomStringConvertible {
    case unreadable
    case tooLarge

    public var description: String {
        switch self {
        case .unreadable: "This image could not be read."
        case .tooLarge: "This image is too large to send, even scaled down."
        }
    }
}

/// Turns a picked, pasted or photographed image (any format ImageIO reads: HEIC, JPEG, PNG…) into what Pi takes:
/// scaled to `ImageSizing.maxEdge`, turned upright, and encoded again as JPEG (or a small transparent PNG). The new
/// file carries no metadata: no location, camera or date from the original leaves the phone.
public enum ImageEncoder {
    public static func encode(_ data: Data) -> Result<ImageAttachment, ImageEncodingError> {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) > 0,
              let image = scaled(source, maxEdge: ImageSizing.maxEdge)
        else { return .failure(.unreadable) }
        let alpha: Bool = switch image.alphaInfo {
        case .none, .noneSkipFirst, .noneSkipLast: false
        default: true
        }
        if alpha, let png = write(image, type: .png, quality: nil), ImageSizing.keepPng(hasAlpha: true, pngBytes: png.count) {
            return ImageSizing.fits(png.count) ? .success(ImageAttachment(base64: png.base64EncodedString(), mimeType: "image/png")) : .failure(.tooLarge)
        }
        // JPEG has no alpha: transparent pixels go on white rather than black.
        guard let opaque = alpha ? onWhite(image) : image, let jpeg = write(opaque, type: .jpeg, quality: ImageSizing.jpegQuality) else {
            return .failure(.unreadable)
        }
        return ImageSizing.fits(jpeg.count) ? .success(ImageAttachment(base64: jpeg.base64EncodedString(), mimeType: "image/jpeg")) : .failure(.tooLarge)
    }

    /// A small upright preview for the composer (`ImageSizing.thumbEdge`), or nil.
    public static func thumbnail(_ data: Data, edge: Int = ImageSizing.thumbEdge) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return scaled(source, maxEdge: edge)
    }

    /// The first frame, upright (EXIF orientation applied), its long edge at most `maxEdge`.
    private static func scaled(_ source: CGImageSource, maxEdge: Int) -> CGImage? {
        let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let width = props?[kCGImagePropertyPixelWidth] as? Int ?? 0
        let height = props?[kCGImagePropertyPixelHeight] as? Int ?? 0
        let (w, h) = ImageSizing.target(width, height, maxEdge: maxEdge)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(w, h, 1),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    private static func onWhite(_ image: CGImage) -> CGImage? {
        guard let context = CGContext(data: nil, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        let rect = CGRect(x: 0, y: 0, width: image.width, height: image.height)
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        context.fill(rect)
        context.draw(image, in: rect)
        return context.makeImage()
    }

    private static func write(_ image: CGImage, type: UTType, quality: Double?) -> Data? {
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, type.identifier as CFString, 1, nil) else { return nil }
        var options: [CFString: Any] = [:]
        if let quality { options[kCGImageDestinationLossyCompressionQuality] = quality }
        CGImageDestinationAddImage(destination, image, options as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return out as Data
    }
}
