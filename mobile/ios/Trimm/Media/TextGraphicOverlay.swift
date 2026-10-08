import Foundation
import CoreGraphics
import CoreText
import UIKit
import CoreImage

class TextGraphicOverlay: @unchecked Sendable {
    private let text: String
    private let blueprintJson: String
    private let startMs: Int64
    private let endMs: Int64
    private let videoWidth: Int
    private let videoHeight: Int

    private var cachedImage: CIImage?

    private var posX: CGFloat = 0
    private var posY: CGFloat = 0
    private var configScale: CGFloat = 1
    private var rotation: CGFloat = 0

    init(text: String, blueprintJson: String, startMs: Int64, endMs: Int64, videoWidth: Int = 1080, videoHeight: Int = 1920) {
        self.text = text
        self.blueprintJson = blueprintJson
        self.startMs = startMs
        self.endMs = endMs
        self.videoWidth = videoWidth
        self.videoHeight = videoHeight
    }

    func getImage(presentationTimeUs: Int64) -> CIImage? {
        let timeMs = presentationTimeUs / 1000
        if timeMs < startMs || timeMs > endMs {
            return nil
        }
        if cachedImage == nil {
            cachedImage = generateImage()
        }
        return cachedImage
    }

    private func parseColor(_ hex: String?, defaultColor: UIColor) -> UIColor {
        guard let hex = hex, hex.hasPrefix("#") else { return defaultColor }
        var hexSanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        hexSanitized = hexSanitized.replacingOccurrences(of: "#", with: "")

        var rgb: UInt64 = 0
        Scanner(string: hexSanitized).scanHexInt64(&rgb)

        if hexSanitized.count == 6 {
            return UIColor(
                red: CGFloat((rgb & 0xFF0000) >> 16) / 255.0,
                green: CGFloat((rgb & 0x00FF00) >> 8) / 255.0,
                blue: CGFloat(rgb & 0x0000FF) / 255.0,
                alpha: 1.0
            )
        }
        return defaultColor
    }

    private func generateImage() -> CIImage? {
        let width = CGFloat(1000)
        let height = width * CGFloat(videoHeight) / CGFloat(videoWidth)
        let size = CGSize(width: width, height: height)

        UIGraphicsBeginImageContextWithOptions(size, false, 1.0)
        guard let context = UIGraphicsGetCurrentContext() else { return nil }

        let cx = width / 2.0
        let cy = height / 2.0

        if let data = blueprintJson.data(using: .utf8),
           let blueprint = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            
            if let layout = blueprint["layout"] as? [String: Any] {
                posX = layout["posX"] as? CGFloat ?? 0
                posY = layout["posY"] as? CGFloat ?? 0
                configScale = layout["scale"] as? CGFloat ?? 1
                rotation = layout["rotation"] as? CGFloat ?? 0
            }

            if let shapes = blueprint["backgroundShapes"] as? [[String: Any]] {
                for shape in shapes {
                    let type = shape["type"] as? String ?? ""
                    let fillColor = parseColor(shape["color"] as? String, defaultColor: .clear)
                    let strokeColor = parseColor(shape["strokeColor"] as? String, defaultColor: .clear)
                    let strokeWidth = shape["strokeWidth"] as? CGFloat ?? 0

                    context.saveGState()

                    if type == "starburst" {
                        let points = shape["points"] as? Int ?? 16
                        let innerRadius = shape["innerRadius"] as? CGFloat ?? 200.0
                        let outerRadius = shape["outerRadius"] as? CGFloat ?? 400.0

                        context.beginPath()
                        for p in 0..<(points * 2) {
                            let radius = (p % 2 == 0) ? outerRadius : innerRadius
                            let angle = CGFloat(p) * .pi / CGFloat(points)
                            let px = cx + radius * cos(angle)
                            let py = cy + radius * sin(angle)
                            if p == 0 {
                                context.move(to: CGPoint(x: px, y: py))
                            } else {
                                context.addLine(to: CGPoint(x: px, y: py))
                            }
                        }
                        context.closePath()
                    } else if type == "rectangle" {
                        let rw = shape["width"] as? CGFloat ?? 400
                        let rh = shape["height"] as? CGFloat ?? 200
                        let r = CGRect(x: cx - rw/2, y: cy - rh/2, width: rw, height: rh)
                        let cornerRadius = shape["cornerRadius"] as? CGFloat ?? 0
                        let path = UIBezierPath(roundedRect: r, cornerRadius: cornerRadius)
                        context.addPath(path.cgPath)
                    }

                    fillColor.setFill()
                    context.fillPath()

                    if strokeWidth > 0 {
                        strokeColor.setStroke()
                        context.setLineWidth(strokeWidth)
                        context.strokePath()
                    }

                    context.restoreGState()
                }
            }
            
            let textStyle = blueprint["textStyle"] as? [String: Any] ?? [:]
            let textColor = parseColor(textStyle["color"] as? String, defaultColor: .white)
            let fontSize = textStyle["fontSize"] as? CGFloat ?? 120.0
            
            let paragraphStyle = NSMutableParagraphStyle()
            paragraphStyle.alignment = .center
            
            let font = UIFont.systemFont(ofSize: fontSize, weight: .bold)
            
            // Add text stroke for better visibility
            let attributes: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: textColor,
                .strokeColor: UIColor.black,
                .strokeWidth: -3.0,
                .paragraphStyle: paragraphStyle
            ]
            
            let textSize = text.boundingRect(with: CGSize(width: width - 40, height: .greatestFiniteMagnitude), options: .usesLineFragmentOrigin, attributes: attributes, context: nil).size
            
            // If no layout is provided, default to centering near the bottom
            if blueprint["layout"] == nil {
                posY = -0.35 // 35% down from center (near bottom)
            }
            
            let textRect = CGRect(x: cx - textSize.width/2, y: cy - textSize.height/2, width: textSize.width, height: textSize.height)
            
            text.draw(in: textRect, withAttributes: attributes)
        }

        let cgImage = UIGraphicsGetImageFromCurrentImageContext()?.cgImage
        UIGraphicsEndImageContext()

        if let cgImage = cgImage {
            // Apply layout transformations (Scale and translate)
            var transform = CGAffineTransform(translationX: (posX * width / 2), y: -(posY * height / 2))
            transform = transform.scaledBy(x: configScale, y: configScale)
            
            let videoScale = CGFloat(videoWidth) / width
            transform = transform.concatenating(CGAffineTransform(scaleX: videoScale, y: videoScale))
            
            return CIImage(cgImage: cgImage).transformed(by: transform)
        }
        
        return nil
    }
}
