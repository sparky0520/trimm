import Foundation
import CryptoKit

public enum ExecutionLocation: String, Codable {
    case localDevice = "LOCAL_DEVICE"
    case externalService = "EXTERNAL_SERVICE"
}

public struct AnalyzerIdentity: Codable, Equatable {
    public let name: String
    public let version: String
    public let execution: ExecutionLocation
    
    public init(name: String, version: String, execution: ExecutionLocation) {
        self.name = name
        self.version = version
        self.execution = execution
    }
}

public struct EvidenceProvenance: Codable, Equatable {
    public let analyzer: AnalyzerIdentity
    public let sourceFingerprint: String
    public let derivedFrom: [String]
    public let note: String?
    
    public init(analyzer: AnalyzerIdentity, sourceFingerprint: String, derivedFrom: [String] = [], note: String? = nil) {
        self.analyzer = analyzer
        self.sourceFingerprint = sourceFingerprint
        self.derivedFrom = derivedFrom
        self.note = note
    }
}

public protocol EvidenceObservation: Codable {
    var id: String { get }
    var sourceFingerprint: String { get }
    var range: TimeRange { get }
    var confidence: Float { get }
    var provenance: EvidenceProvenance { get }
}

public enum VisualKind: String, Codable {
    case face = "FACE"
    case screenUI = "SCREEN_UI"
    case product = "PRODUCT"
    case ocrText = "OCR_TEXT"
    case salience = "SALIENCE"
    case motion = "MOTION"
    case blur = "BLUR"
    case shake = "SHAKE"
    case exposure = "EXPOSURE"
    case cropSafety = "CROP_SAFETY"
}

public struct NormalizedBounds: Codable, Equatable {
    public let left: Float
    public let top: Float
    public let right: Float
    public let bottom: Float
    
    public init(left: Float, top: Float, right: Float, bottom: Float) {
        precondition(left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right > left && bottom > top)
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }
}

public struct ShotEvidence: EvidenceObservation, Equatable {
    public let id: String
    public let sourceFingerprint: String
    public let range: TimeRange
    public let confidence: Float
    public let provenance: EvidenceProvenance
    public let representativeFrameIds: [String]
    
    public init(id: String, sourceFingerprint: String, range: TimeRange, confidence: Float, provenance: EvidenceProvenance, representativeFrameIds: [String]) {
        self.id = id
        self.sourceFingerprint = sourceFingerprint
        self.range = range
        self.confidence = confidence
        self.provenance = provenance
        self.representativeFrameIds = representativeFrameIds
    }
}

public struct FrameEvidence: EvidenceObservation, Equatable {
    public let id: String
    public let sourceFingerprint: String
    public let range: TimeRange
    public let confidence: Float
    public let provenance: EvidenceProvenance
    public let artifactPath: String
    public let width: Int
    public let height: Int
    
    public init(id: String, sourceFingerprint: String, range: TimeRange, confidence: Float, provenance: EvidenceProvenance, artifactPath: String, width: Int, height: Int) {
        self.id = id
        self.sourceFingerprint = sourceFingerprint
        self.range = range
        self.confidence = confidence
        self.provenance = provenance
        self.artifactPath = artifactPath
        self.width = width
        self.height = height
    }
}

public struct VisualEvidence: EvidenceObservation, Equatable {
    public let id: String
    public let sourceFingerprint: String
    public let range: TimeRange
    public let confidence: Float
    public let provenance: EvidenceProvenance
    public let kind: VisualKind
    public let value: Float?
    public let text: String?
    public let bounds: NormalizedBounds?
    
    public init(id: String, sourceFingerprint: String, range: TimeRange, confidence: Float, provenance: EvidenceProvenance, kind: VisualKind, value: Float? = nil, text: String? = nil, bounds: NormalizedBounds? = nil) {
        self.id = id
        self.sourceFingerprint = sourceFingerprint
        self.range = range
        self.confidence = confidence
        self.provenance = provenance
        self.kind = kind
        self.value = value
        self.text = text
        self.bounds = bounds
    }
}

public enum SpeechKind: String, Codable {
    case word = "WORD"
    case sentence = "SENTENCE"
    case speakerRegion = "SPEAKER_REGION"
    case pause = "PAUSE"
    case disfluency = "DISFLUENCY"
}

public struct SpeechEvidence: EvidenceObservation, Equatable {
    public let id: String
    public let sourceFingerprint: String
    public let range: TimeRange
    public let confidence: Float
    public let provenance: EvidenceProvenance
    public let kind: SpeechKind
    public let text: String?
    public let speakerId: String?
    
    public init(id: String, sourceFingerprint: String, range: TimeRange, confidence: Float, provenance: EvidenceProvenance, kind: SpeechKind, text: String? = nil, speakerId: String? = nil) {
        self.id = id
        self.sourceFingerprint = sourceFingerprint
        self.range = range
        self.confidence = confidence
        self.provenance = provenance
        self.kind = kind
        self.text = text
        self.speakerId = speakerId
    }
}

public enum SoundKind: String, Codable {
    case loudness = "LOUDNESS"
    case noiseFloor = "NOISE_FLOOR"
    case speechLevel = "SPEECH_LEVEL"
    case snr = "SNR"
    case clipping = "CLIPPING"
    case music = "MUSIC"
    case silence = "SILENCE"
    case transient = "TRANSIENT"
    case measurementUncertainty = "MEASUREMENT_UNCERTAINTY"
}

public struct SoundEvidence: EvidenceObservation, Equatable {
    public let id: String
    public let sourceFingerprint: String
    public let range: TimeRange
    public let confidence: Float
    public let provenance: EvidenceProvenance
    public let kind: SoundKind
    public let value: Float?
    public let unit: String?
    
    public init(id: String, sourceFingerprint: String, range: TimeRange, confidence: Float, provenance: EvidenceProvenance, kind: SoundKind, value: Float? = nil, unit: String? = nil) {
        self.id = id
        self.sourceFingerprint = sourceFingerprint
        self.range = range
        self.confidence = confidence
        self.provenance = provenance
        self.kind = kind
        self.value = value
        self.unit = unit
    }
}

public enum AnalyzerStatus: String, Codable {
    case complete = "COMPLETE"
    case failed = "FAILED"
    case unsupported = "UNSUPPORTED"
    case cached = "CACHED"
}

public struct AnalyzerRun: Codable, Equatable {
    public let analyzer: AnalyzerIdentity
    public let status: AnalyzerStatus
    public let startedAtEpochMs: Int64
    public let completedAtEpochMs: Int64
    public let observationCount: Int
    public let safeError: String?
    
    public init(analyzer: AnalyzerIdentity, status: AnalyzerStatus, startedAtEpochMs: Int64, completedAtEpochMs: Int64, observationCount: Int, safeError: String? = nil) {
        self.analyzer = analyzer
        self.status = status
        self.startedAtEpochMs = startedAtEpochMs
        self.completedAtEpochMs = completedAtEpochMs
        self.observationCount = observationCount
        self.safeError = safeError
    }
}

public struct ExternalDisclosure: Codable, Equatable {
    public let service: String
    public let data: String
    public let purpose: String
    
    public init(service: String, data: String, purpose: String) {
        self.service = service
        self.data = data
        self.purpose = purpose
    }
}

public struct EvidenceDisclosure: Codable, Equatable {
    public let computedLocally: [String]
    public let sentExternally: [ExternalDisclosure]
    
    public init(computedLocally: [String], sentExternally: [ExternalDisclosure]) {
        self.computedLocally = computedLocally
        self.sentExternally = sentExternally
    }
}

// Wrapper for EvidenceObservation to support polymorphic decoding
public enum AnyEvidenceObservation: Codable, Equatable {
    case shot(ShotEvidence)
    case frame(FrameEvidence)
    case visual(VisualEvidence)
    case speech(SpeechEvidence)
    case sound(SoundEvidence)
    
    public var base: EvidenceObservation {
        switch self {
        case .shot(let e): return e
        case .frame(let e): return e
        case .visual(let e): return e
        case .speech(let e): return e
        case .sound(let e): return e
        }
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let shot = try? container.decode(ShotEvidence.self) {
            self = .shot(shot)
        } else if let frame = try? container.decode(FrameEvidence.self) {
            self = .frame(frame)
        } else if let visual = try? container.decode(VisualEvidence.self) {
            self = .visual(visual)
        } else if let speech = try? container.decode(SpeechEvidence.self) {
            self = .speech(speech)
        } else if let sound = try? container.decode(SoundEvidence.self) {
            self = .sound(sound)
        } else {
            throw DecodingError.typeMismatch(AnyEvidenceObservation.self, DecodingError.Context(codingPath: decoder.codingPath, debugDescription: "Unknown evidence observation type"))
        }
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .shot(let e): try container.encode(e)
        case .frame(let e): try container.encode(e)
        case .visual(let e): try container.encode(e)
        case .speech(let e): try container.encode(e)
        case .sound(let e): try container.encode(e)
        }
    }
}

public struct EvidenceGraph: Codable, Equatable {
    public let schemaVersion: Int
    public let sourceFingerprint: String
    public let sourceDurationMs: Int64
    public let createdAtEpochMs: Int64
    public let observations: [AnyEvidenceObservation]
    public let analyzerRuns: [AnalyzerRun]
    public let disclosure: EvidenceDisclosure
    
    public init(schemaVersion: Int = 1, sourceFingerprint: String, sourceDurationMs: Int64, createdAtEpochMs: Int64, observations: [AnyEvidenceObservation], analyzerRuns: [AnalyzerRun], disclosure: EvidenceDisclosure) {
        precondition(schemaVersion == 1)
        precondition(sourceDurationMs > 0)
        let uniqueIds = Set(observations.map { $0.base.id })
        precondition(uniqueIds.count == observations.count)
        for obs in observations {
            precondition(obs.base.sourceFingerprint == sourceFingerprint)
            precondition(obs.base.range.endMs <= sourceDurationMs)
            precondition(obs.base.confidence >= 0 && obs.base.confidence <= 1)
        }
        
        self.schemaVersion = schemaVersion
        self.sourceFingerprint = sourceFingerprint
        self.sourceDurationMs = sourceDurationMs
        self.createdAtEpochMs = createdAtEpochMs
        self.observations = observations
        self.analyzerRuns = analyzerRuns
        self.disclosure = disclosure
    }
}

public func stableEvidenceId(sourceFingerprint: String, analyzer: AnalyzerIdentity, kind: String, range: TimeRange, discriminator: String = "") -> String {
    let rawStr = "\(sourceFingerprint)|\(analyzer.name)|\(analyzer.version)|\(kind)|\(range.startMs)|\(range.endMs)|\(discriminator)"
    let hash = SHA256.hash(data: Data(rawStr.utf8))
    let hex = hash.compactMap { String(format: "%02x", $0) }.joined()
    return String(hex.prefix(16))
}
