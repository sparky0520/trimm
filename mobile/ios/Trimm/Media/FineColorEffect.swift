import Foundation
import CoreImage

struct FineColorSettings {
    var exposure: Float = 0
    var temperature: Float = 0
    var contrast: Float = 1
    var saturation: Float = 1
    var highlights: Float = 0
    var shadows: Float = 0
    var skinProtection: Float = 0
}

enum FineColorMath {
    static func settings(from operations: [EditOperation]) -> FineColorSettings {
        var result = FineColorSettings()
        for operation in operations {
            guard case let .setColorGrade(_, enabled, grade, _, parameter, value) = operation, enabled else { continue }
            if let grade = grade {
                result = grade.toFineSettings()
            }
            guard let value = value else { continue }
            switch parameter {
            case "exposure": result.exposure = value
            case "temperature": result.temperature = value
            case "contrast": result.contrast = value
            case "saturation": result.saturation = value
            case "highlights": result.highlights = value
            case "shadows": result.shadows = value
            case "skinProtection": result.skinProtection = value
            default: break
            }
        }
        return result
    }

    static func adjust(red: Float, green: Float, blue: Float, settings: FineColorSettings) -> [Float] {
        let original = [red, green, blue]
        var r = red + settings.exposure * 0.18 + settings.temperature * 0.08
        var g = green + settings.exposure * 0.18
        var b = blue + settings.exposure * 0.18 - settings.temperature * 0.08
        
        var lumaVal = luma(r, g, b)
        r = lumaVal + (r - lumaVal) * settings.saturation
        g = lumaVal + (g - lumaVal) * settings.saturation
        b = lumaVal + (b - lumaVal) * settings.saturation
        
        r = (r - 0.5) * settings.contrast + 0.5
        g = (g - 0.5) * settings.contrast + 0.5
        b = (b - 0.5) * settings.contrast + 0.5
        
        lumaVal = max(0, min(1, luma(r, g, b)))
        let tonal = settings.shadows * 0.18 * (1 - lumaVal) * (1 - lumaVal) +
            settings.highlights * 0.18 * lumaVal * lumaVal
        r += tonal; g += tonal; b += tonal
        
        let protection = settings.skinProtection * skinLikelihood(r: original[0], g: original[1], b: original[2])
        return [
            max(0, min(1, mix(adjusted: r, original: original[0], amount: protection))),
            max(0, min(1, mix(adjusted: g, original: original[1], amount: protection))),
            max(0, min(1, mix(adjusted: b, original: original[2], amount: protection)))
        ]
    }

    private static func luma(_ r: Float, _ g: Float, _ b: Float) -> Float {
        return r * 0.2126 + g * 0.7152 + b * 0.0722
    }

    private static func mix(adjusted: Float, original: Float, amount: Float) -> Float {
        return adjusted * (1 - amount) + original * amount
    }

    private static func skinLikelihood(r: Float, g: Float, b: Float) -> Float {
        let maximum = max(r, max(g, b))
        let minimum = min(r, min(g, b))
        if r <= g || g <= b || maximum - minimum < 0.06 || r < 0.2 { return 0 }
        let warmth = max(0, min(1, (r - b) / 0.45))
        let greenBalance = max(0, min(1, 1 - abs(g - (r + b) * 0.5) / 0.3))
        return warmth * greenBalance
    }
}

extension ColorGrade {
    func toFineSettings() -> FineColorSettings {
        return FineColorSettings(
            exposure: exposure,
            temperature: temperature,
            contrast: contrast,
            saturation: saturation,
            highlights: highlights,
            shadows: shadows,
            skinProtection: skinProtection
        )
    }
}

class FineColorEffect {
    private static let lutSize = 17
    
    static func createColorCube(operations: [EditOperation]) -> CIFilter? {
        if operations.isEmpty { return nil }
        
        let settings = FineColorMath.settings(from: operations)
        
        let size = lutSize
        var cubeData = [Float](repeating: 0, count: size * size * size * 4)
        var offset = 0
        
        for b in 0..<size {
            for g in 0..<size {
                for r in 0..<size {
                    let rNorm = Float(r) / Float(size - 1)
                    let gNorm = Float(g) / Float(size - 1)
                    let bNorm = Float(b) / Float(size - 1)
                    
                    let adjusted = FineColorMath.adjust(red: rNorm, green: gNorm, blue: bNorm, settings: settings)
                    
                    // CoreImage CIColorCube expects premultiplied alpha float data (R,G,B,A)
                    cubeData[offset] = adjusted[0]
                    cubeData[offset+1] = adjusted[1]
                    cubeData[offset+2] = adjusted[2]
                    cubeData[offset+3] = 1.0
                    offset += 4
                }
            }
        }
        
        let data = Data(bytes: cubeData, count: cubeData.count * MemoryLayout<Float>.size)
        
        guard let filter = CIFilter(name: "CIColorCube") else { return nil }
        filter.setValue(size, forKey: "inputCubeDimension")
        filter.setValue(data, forKey: "inputCubeData")
        return filter
    }
}
