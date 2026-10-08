import Foundation
import CoreGraphics

struct VideoDSL: Codable {
    let project: ProjectConfig
    let timeline: Timeline
}

struct ProjectConfig: Codable {
    let dimensions: Dimensions
    let fps: Int
    let durationMs: Int
}

struct Dimensions: Codable {
    let width: CGFloat
    let height: CGFloat
}

struct Timeline: Codable {
    let layers: [Layer]
}

struct Layer: Codable {
    let id: String
    let type: String
    let source: LayerSource?
    let startMs: Int
    let durationMs: Int
    let spatial: Spatial?
    let compositing: Compositing?
    let audio: Audio?
    let effects: [Effect]?
}

struct LayerSource: Codable {
    let url: String
    let mediaStartTimeMs: Int?
}

struct Spatial: Codable {
    let anchor: Point?
    let keyframes: [SpatialKeyframe]
}

struct Point: Codable {
    let x: CGFloat
    let y: CGFloat
}

struct SpatialKeyframe: Codable {
    let timeMs: Int
    let x: CGFloat?
    let y: CGFloat?
    let scale: CGFloat?
    let rotation: CGFloat?
    let easing: String?
}

struct Compositing: Codable {
    let blendMode: String?
    let opacity: Opacity?
}

struct Opacity: Codable {
    let keyframes: [OpacityKeyframe]
}

struct OpacityKeyframe: Codable {
    let timeMs: Int
    let value: Float
    let easing: String?
}

struct Audio: Codable {
    let volume: Volume?
}

struct Volume: Codable {
    let keyframes: [VolumeKeyframe]
}

struct VolumeKeyframe: Codable {
    let timeMs: Int
    let value: Float
    let easing: String?
}

struct Effect: Codable {
    let type: String
    let matrix: [Float]?
}
