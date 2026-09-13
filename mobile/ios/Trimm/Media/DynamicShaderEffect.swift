import Foundation
import CoreImage

class DynamicShaderEffect {
    // In iOS, without dynamic MSL compilation, we map specific "shaderType" or "preset" strings
    // to built in CoreImage filters.
    static func applyPreset(name: String, to image: CIImage, progress: Float) -> CIImage? {
        let filterName: String
        switch name {
        case "blur": filterName = "CIGaussianBlur"
        case "sepia": filterName = "CISepiaTone"
        case "invert": filterName = "CIColorInvert"
        case "bloom": filterName = "CIBloom"
        default: return image
        }
        
        guard let filter = CIFilter(name: filterName) else { return image }
        filter.setValue(image, forKey: kCIInputImageKey)
        
        // Map progress (0.0 - 1.0) to filter intensity/radius if applicable
        if filterName == "CIGaussianBlur" {
            filter.setValue(progress * 20.0, forKey: kCIInputRadiusKey)
        } else if filterName == "CISepiaTone" {
            filter.setValue(progress, forKey: kCIInputIntensityKey)
        } else if filterName == "CIBloom" {
            filter.setValue(progress * 1.5, forKey: kCIInputIntensityKey)
        }
        
        return filter.outputImage ?? image
    }
}
