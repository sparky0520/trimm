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

    init(text: String, blueprintJson: String, startMs: Int64, endMs: Int64, videoWidth: Int, videoHeight: Int) {
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
        } else if hexSanitized.count == 8 {
            return UIColor(
                red: CGFloat((rgb & 0xFF000000) >> 24) / 255.0,
                green: CGFloat((rgb & 0x00FF0000) >> 16) / 255.0,
                blue: CGFloat((rgb & 0x0000FF00) >> 8) / 255.0,
                alpha: CGFloat(rgb & 0x000000FF) / 255.0
            )
        }
        return defaultColor
    }

    private func generateImage() -> CIImage? {
        let width = CGFloat(videoWidth)
        let height = CGFloat(videoHeight)
        let size = CGSize(width: width, height: height)

        UIGraphicsBeginImageContextWithOptions(size, false, 1.0)
        guard let context = UIGraphicsGetCurrentContext() else { return nil }

        var posX: CGFloat = 0
        var posY: CGFloat = 0
        var configScale: CGFloat = 1.0
        var rotation: CGFloat = 0
        
        let cx = width / 2.0
        let cy = height / 2.0

        if let data = blueprintJson.data(using: .utf8),
           let blueprint = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            
            if let layout = blueprint["layout"] as? [String: Any] {
                posX = layout["posX"] as? CGFloat ?? 0
                posY = layout["posY"] as? CGFloat ?? 0
                configScale = layout["scale"] as? CGFloat ?? 1
                rotation = layout["rotation"] as? CGFloat ?? 0
            } else {
                posY = -0.35 // 35% down from center (near bottom)
            }
            
            let translationX = posX * width / 2
            let translationY = -(posY * height / 2) // CoreGraphics Y goes down
            
            context.translateBy(x: cx + translationX, y: cy + translationY)
            context.scaleBy(x: configScale, y: configScale)
            context.rotate(by: rotation)
            
            // Map AI's 1000px coordinate system values to the actual video size using the shortest dimension
            let scaleFactor = min(width, height) / 1000.0

            // --- Compute Text Size First for Flexible Backgrounds ---
            let textStyle = blueprint["textStyle"] as? [String: Any] ?? [:]
            let textColor = parseColor(textStyle["color"] as? String, defaultColor: .white)
            let strokeColor = parseColor(textStyle["strokeColor"] as? String, defaultColor: .black)
            let strokeWidth = (textStyle["strokeWidth"] as? CGFloat ?? 0.0) * scaleFactor
            let rawFontSize = textStyle["fontSize"] as? CGFloat ?? 100.0 // Default size expected by AI
            let fontSize = rawFontSize * scaleFactor
            
            let paragraphStyle = NSMutableParagraphStyle()
            paragraphStyle.alignment = .center
            
            let font = UIFont.systemFont(ofSize: fontSize, weight: .bold)
            
            let shadow = NSShadow()
            shadow.shadowColor = UIColor.black
            shadow.shadowOffset = .zero
            shadow.shadowBlurRadius = 4.0
            
            var attributes: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: textColor,
                .shadow: shadow,
                .paragraphStyle: paragraphStyle
            ]
            
            if strokeWidth > 0 {
                attributes[.strokeColor] = strokeColor
                attributes[.strokeWidth] = -strokeWidth // Negative means stroke and fill
            }
            
            let attributedText: NSAttributedString
            if let segments = blueprint["segments"] as? [[String: Any]] {
                let mutableAttrStr = NSMutableAttributedString()
                for seg in segments {
                    var segAttrs = attributes
                    if let colorHex = seg["color"] as? String {
                        segAttrs[.foregroundColor] = parseColor(colorHex, defaultColor: textColor)
                    }
                    let segText = seg["text"] as? String ?? ""
                    mutableAttrStr.append(NSAttributedString(string: segText, attributes: segAttrs))
                }
                attributedText = mutableAttrStr
            } else {
                attributedText = NSAttributedString(string: text, attributes: attributes)
            }
            
            // Allow wrapping with 40px padding
            let maxTextWidth = width - 40
            let textSize = attributedText.boundingRect(with: CGSize(width: maxTextWidth, height: .greatestFiniteMagnitude), options: .usesLineFragmentOrigin, context: nil).size
            
            // --- Draw Background Shapes ---
            if let shapes = blueprint["backgroundShapes"] as? [[String: Any]] {
                for shape in shapes {
                    let type = shape["type"] as? String ?? ""
                    let fillColor = parseColor(shape["color"] as? String, defaultColor: .clear)
                    let strokeColor = parseColor(shape["strokeColor"] as? String, defaultColor: .clear)
                    let strokeWidth = (shape["strokeWidth"] as? CGFloat ?? 0) * scaleFactor

                    context.saveGState()

                    if type == "starburst" {
                        let points = shape["points"] as? Int ?? 16
                        let innerRadius = (shape["innerRadius"] as? CGFloat ?? 200.0) * scaleFactor
                        let outerRadius = (shape["outerRadius"] as? CGFloat ?? 400.0) * scaleFactor

                        context.beginPath()
                        for p in 0..<(points * 2) {
                            let radius = (p % 2 == 0) ? outerRadius : innerRadius
                            let angle = CGFloat(p) * .pi / CGFloat(points)
                            let px = radius * cos(angle)
                            let py = radius * sin(angle)
                            if p == 0 {
                                context.move(to: CGPoint(x: px, y: py))
                            } else {
                                context.addLine(to: CGPoint(x: px, y: py))
                            }
                        }
                        context.closePath()
                    } else if type == "rectangle" {
                        let requestedW = (shape["width"] as? CGFloat ?? 400) * scaleFactor
                        let requestedH = (shape["height"] as? CGFloat ?? 200) * scaleFactor
                        let paddingX = 60.0 * scaleFactor
                        let paddingY = 30.0 * scaleFactor
                        let rw = max(requestedW, textSize.width + paddingX)
                        let rh = max(requestedH, textSize.height + paddingY)
                        let r = CGRect(x: -rw/2, y: -rh/2, width: rw, height: rh)
                        let cornerRadius = (shape["cornerRadius"] as? CGFloat ?? 0) * scaleFactor
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
            
            // --- Draw Text ---
            let textRect = CGRect(x: -textSize.width/2, y: -textSize.height/2, width: textSize.width, height: textSize.height)
            
            context.setLineJoin(.round)
            
            if strokeWidth > 0 {
                // Draw stroke layer first
                attributedText.draw(in: textRect)
                
                // Draw fill layer on top to cover overlapping strokes
                let fillText = NSMutableAttributedString(attributedString: attributedText)
                let fullRange = NSRange(location: 0, length: fillText.length)
                fillText.removeAttribute(.strokeWidth, range: fullRange)
                fillText.removeAttribute(.strokeColor, range: fullRange)
                fillText.removeAttribute(.shadow, range: fullRange)
                
                fillText.draw(in: textRect)
            } else {
                attributedText.draw(in: textRect)
            }
        }

        let cgImage = UIGraphicsGetImageFromCurrentImageContext()?.cgImage
        UIGraphicsEndImageContext()

        if let cgImage = cgImage {
            return CIImage(cgImage: cgImage)
        }
        
        return nil
    }
}
