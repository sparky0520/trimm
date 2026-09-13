import Foundation
import CoreImage
import CoreImage.CIFilterBuiltins

class TransitionGraphicOverlay {
    static func createTransitionFilter(name: String, sourceImage: CIImage, targetImage: CIImage, progress: Float) -> CIImage? {
        // Most CIFilters take 'inputImage' and 'inputTargetImage'
        // Progress is often 'inputTime' (0.0 to 1.0)
        let filterName = name.isEmpty ? "CIDissolveTransition" : name
        
        guard let filter = CIFilter(name: filterName) else {
            // Fallback to cross dissolve
            return fallbackDissolve(source: sourceImage, target: targetImage, progress: progress)
        }
        
        filter.setValue(sourceImage, forKey: kCIInputImageKey)
        filter.setValue(targetImage, forKey: kCIInputTargetImageKey)
        
        if filter.inputKeys.contains(kCIInputTimeKey) {
            filter.setValue(progress, forKey: kCIInputTimeKey)
        }
        
        return filter.outputImage
    }
    
    private static func fallbackDissolve(source: CIImage, target: CIImage, progress: Float) -> CIImage? {
        guard let filter = CIFilter(name: "CIDissolveTransition") else { return nil }
        filter.setValue(source, forKey: kCIInputImageKey)
        filter.setValue(target, forKey: kCIInputTargetImageKey)
        filter.setValue(progress, forKey: kCIInputTimeKey)
        return filter.outputImage
    }
}
