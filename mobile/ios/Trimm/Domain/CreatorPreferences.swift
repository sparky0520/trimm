import Foundation

public enum CreatorPreferenceKind: String, Codable {
    case brandAsset = "BRAND_ASSET", terminology = "TERMINOLOGY", captionRule = "CAPTION_RULE", pacing = "PACING", motionTolerance = "MOTION_TOLERANCE"
}

public struct CreatorPreference: Codable, Equatable {
    public let kind: CreatorPreferenceKind
    public let value: String
    public let decisionIds: [String]
    public let confirmations: Int
    public let explicit: Bool
    public let enabled: Bool
    
    public init(kind: CreatorPreferenceKind, value: String, decisionIds: [String], confirmations: Int = 1, explicit: Bool = false, enabled: Bool = true) {
        precondition(!value.trimmingCharacters(in: .whitespaces).isEmpty)
        precondition(confirmations > 0)
        precondition(!decisionIds.isEmpty)
        self.kind = kind
        self.value = value
        self.decisionIds = decisionIds
        self.confirmations = confirmations
        self.explicit = explicit
        self.enabled = enabled
    }
    
    public var active: Bool {
        return enabled && (explicit || confirmations >= 2)
    }
}

public struct CreatorPreferenceProfile: Codable, Equatable {
    public let schemaVersion: Int
    public let entries: [CreatorPreference]
    
    public init(schemaVersion: Int = 1, entries: [CreatorPreference] = []) {
        precondition(schemaVersion == 1)
        self.schemaVersion = schemaVersion
        self.entries = entries
    }
    
    public var activeEntries: [CreatorPreference] {
        return entries.filter { $0.active }
    }
    
    public func record(kind: CreatorPreferenceKind, value: String, decisionId: String, explicit: Bool = false) -> CreatorPreferenceProfile {
        let clean = String(value.trimmingCharacters(in: .whitespaces).prefix(500))
        precondition(!clean.isEmpty && !decisionId.isEmpty)
        
        let prior = entries.first { $0.kind == kind && $0.value.caseInsensitiveCompare(clean) == .orderedSame }
        let next: CreatorPreference
        if let prior = prior {
            var newDecisionIds = prior.decisionIds
            if !newDecisionIds.contains(decisionId) {
                newDecisionIds.append(decisionId)
            }
            let suffix = Array(newDecisionIds.suffix(20))
            let confirmAddition = prior.decisionIds.contains(decisionId) ? 0 : 1
            next = CreatorPreference(
                kind: kind, value: prior.value, decisionIds: suffix,
                confirmations: prior.confirmations + confirmAddition,
                explicit: prior.explicit || explicit, enabled: true
            )
        } else {
            next = CreatorPreference(kind: kind, value: clean, decisionIds: [decisionId], explicit: explicit)
        }
        
        let filtered = entries.filter { !($0.kind == kind && $0.value.caseInsensitiveCompare(clean) == .orderedSame) }
        var updated = filtered + [next]
        updated.sort {
            if $0.kind.rawValue != $1.kind.rawValue { return $0.kind.rawValue < $1.kind.rawValue }
            return $0.value < $1.value
        }
        return CreatorPreferenceProfile(schemaVersion: schemaVersion, entries: updated)
    }
    
    public func setEnabled(kind: CreatorPreferenceKind, value: String, enabled: Bool) -> CreatorPreferenceProfile {
        let updated = entries.map { entry in
            (entry.kind == kind && entry.value == value) ? CreatorPreference(kind: entry.kind, value: entry.value, decisionIds: entry.decisionIds, confirmations: entry.confirmations, explicit: entry.explicit, enabled: enabled) : entry
        }
        return CreatorPreferenceProfile(schemaVersion: schemaVersion, entries: updated)
    }
    
    public func reset(kind: CreatorPreferenceKind? = nil, value: String? = nil) -> CreatorPreferenceProfile {
        if kind == nil {
            return CreatorPreferenceProfile(schemaVersion: schemaVersion, entries: [])
        }
        if value == nil {
            return CreatorPreferenceProfile(schemaVersion: schemaVersion, entries: entries.filter { $0.kind != kind })
        }
        return CreatorPreferenceProfile(schemaVersion: schemaVersion, entries: entries.filter { !($0.kind == kind && $0.value == value) })
    }
}

public struct ResolvedCreatorPreferences: Codable, Equatable {
    public let brandAssets: [String]
    public let terminology: [String]
    public let captionRules: [String]
    public let pacing: String?
    public let motionTolerance: String?
    public let provenanceDecisionIds: [String]
    
    public init(brandAssets: [String] = [], terminology: [String] = [], captionRules: [String] = [], pacing: String? = nil, motionTolerance: String? = nil, provenanceDecisionIds: [String] = []) {
        self.brandAssets = brandAssets
        self.terminology = terminology
        self.captionRules = captionRules
        self.pacing = pacing
        self.motionTolerance = motionTolerance
        self.provenanceDecisionIds = provenanceDecisionIds
    }
}

public enum CreatorPreferenceResolver {
    public static func resolve(profile: CreatorPreferenceProfile, projectIntent: WizardPreferences) -> ResolvedCreatorPreferences {
        let active = profile.activeEntries
        func values(for kind: CreatorPreferenceKind) -> [String] {
            return active.filter { $0.kind == kind }.map { $0.value }
        }
        let learnedPace = values(for: .pacing).last
        
        return ResolvedCreatorPreferences(
            brandAssets: values(for: .brandAsset),
            terminology: values(for: .terminology),
            captionRules: projectIntent.captions ? values(for: .captionRule) : [],
            pacing: (learnedPace?.caseInsensitiveCompare(projectIntent.pace.rawValue) == .orderedSame) ? learnedPace : nil,
            motionTolerance: values(for: .motionTolerance).last,
            provenanceDecisionIds: Array(Set(active.flatMap { $0.decisionIds }))
        )
    }
}

public enum CreatorPreferenceLearner {
    public static func confirmedProposal(profile: CreatorPreferenceProfile, project: MobileProject, proposal: PendingEditorialProposal) -> CreatorPreferenceProfile {
        var learned = profile
        for decision in proposal.plan.decisions where decision.stage == .pacing {
            learned = learned.record(kind: .pacing, value: project.preferences.pace.rawValue, decisionId: decision.id)
        }
        for op in proposal.operations {
            if case .addCaptions(let id, _, _, _, _, let style) = op {
                learned = learned.record(kind: .captionRule, value: style, decisionId: id)
            }
        }
        return learned
    }
}

public struct PersonalizationEvaluation: Codable, Equatable {
    public let baselineAccepted: Int
    public let personalizedAccepted: Int
    public let baselineUngrounded: Int
    public let personalizedUngrounded: Int
    
    public init(baselineAccepted: Int, personalizedAccepted: Int, baselineUngrounded: Int, personalizedUngrounded: Int) {
        self.baselineAccepted = baselineAccepted
        self.personalizedAccepted = personalizedAccepted
        self.baselineUngrounded = baselineUngrounded
        self.personalizedUngrounded = personalizedUngrounded
    }
    
    public var improvesAcceptance: Bool {
        return personalizedAccepted > baselineAccepted
    }
    
    public var avoidsUngroundedIncrease: Bool {
        return personalizedUngrounded <= baselineUngrounded
    }
}
