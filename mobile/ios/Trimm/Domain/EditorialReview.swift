import Foundation

public struct TakeComparison: Codable, Equatable {
    public let groupId: String
    public let sentence: String
    public let selectedTakeId: String?
    public let alternatives: [String]
    public let explanation: String
    public let confidence: Float
}

public struct EditorialRead: Codable, Equatable {
    public let likelyStory: [String]
    public let repeatedTakes: [TakeComparison]
    public let technicalProblems: [String]
    public let usefulProductShots: [String]
    public let uncertainty: [String]
}

public struct ProposalReview: Codable, Equatable {
    public let summary: String
    public let beforeStory: [String]
    public let afterStory: [String]
    public let selectedTakes: [TakeComparison]
    public let removals: [String]
    public let beforeDurationMs: Int64
    public let afterDurationMs: Int64
    public let evidenceIds: [String]
    public let assumptions: [String]
    public let warnings: [String]
}

public enum EditorialReviewBuilder {
    public static func editorialRead(project: MobileProject) -> EditorialRead {
        let groups = project.analysis.takeGroups
        
        var likelyStory = [String]()
        // Placeholder for storyGraph.beats since we don't have StoryGraph implemented yet
        // In full implementation, parse beats and format them.
        
        var usefulProductShots = [String]()
        
        var uncertaintyList = [String]()
        let allFumbles = (project.analysis.speechHints + project.analysis.lowConfidenceFumbles)
        for fumble in allFumbles {
            let surrounding = [fumble.contextBefore, fumble.context, fumble.contextAfter].filter { !$0.isEmpty }.joined(separator: " | ")
            let formattedRange = formatRange(fumble.range)
            let formattedConf = percent(fumble.confidence)
            let trail = surrounding.isEmpty ? "" : ": \(surrounding)"
            uncertaintyList.append("\(fumble.reason) at \(formattedRange) (\(formattedConf) measurement confidence)\(trail)")
        }
        
        for group in groups where !group.takes.dropFirst().isEmpty { // Has alternatives if takes > 1
            if let first = group.takes.first {
                uncertaintyList.append("The repeated sentence “\(first.transcript)” has viable alternatives.")
            }
        }
        uncertaintyList = Array(NSOrderedSet(array: uncertaintyList)) as! [String]
        
        let warnings = Array(NSOrderedSet(array: project.analysis.warnings)) as! [String]
        
        return EditorialRead(
            likelyStory: likelyStory,
            repeatedTakes: groups.map(comparison),
            technicalProblems: warnings,
            usefulProductShots: usefulProductShots,
            uncertainty: uncertaintyList
        )
    }
    
    public static func proposal(project: MobileProject) -> ProposalReview? {
        guard let proposal = project.analysis.pendingProposal else { return nil }
        
        var takeById = [String: TakeGroup]()
        for group in project.analysis.takeGroups {
            for take in group.takes {
                takeById[take.id] = group
            }
        }
        
        var selected = [TakeComparison]()
        var seenTakes = Set<String>()
        for choice in proposal.plan.story {
            if let takeId = choice.selectedTakeId, let group = takeById[takeId] {
                if !seenTakes.contains(group.id) {
                    seenTakes.insert(group.id)
                    selected.append(comparison(group: group))
                }
            }
        }
        
        var removedRanges = [TimeRange]()
        for op in proposal.operations {
            if case .removeRanges(_, let enabled, let ranges, _, _, _) = op, enabled {
                removedRanges.append(contentsOf: ranges)
            }
        }
        
        let removedMs = mergeDuration(ranges: removedRanges)
        let projectDuration = project.orderedSources.reduce(0) { $0 + $1.durationMs }
        
        var warnings = [String]()
        if proposal.expectedRevision != project.revision {
            warnings.append("This proposal targets revision \(proposal.expectedRevision); the project is now revision \(project.revision).")
        }
        warnings.append(contentsOf: project.analysis.warnings)
        for choice in proposal.plan.story where choice.confidence < 0.7 {
            warnings.append("Low confidence: \(choice.rationale)")
        }
        for decision in proposal.plan.decisions where decision.confidence < 0.7 {
            warnings.append("Low confidence: \(decision.rationale)")
        }
        warnings = Array(NSOrderedSet(array: warnings)) as! [String]
        
        var removals = [String]()
        for op in proposal.operations {
            if case .removeRanges(_, _, let ranges, _, let reason, _) = op {
                for range in ranges {
                    let r = reason.isEmpty ? "Range removal" : reason
                    removals.append("\(r) — \(formatRange(range))")
                }
            }
        }
        
        let evidenceIds = Array(NSOrderedSet(array: proposal.evidenceIds)) as! [String]
        
        var assumptions = [String]()
        proposal.plan.story.forEach { assumptions.append(contentsOf: $0.assumptions) }
        proposal.plan.decisions.forEach { assumptions.append(contentsOf: $0.assumptions) }
        proposal.plan.operationPlans.forEach { assumptions.append(contentsOf: $0.assumptions) }
        assumptions = Array(NSOrderedSet(array: assumptions)) as! [String]
        
        return ProposalReview(
            summary: proposal.summary,
            beforeStory: [], // Placeholder for graph?.beats
            afterStory: proposal.plan.story.map { $0.beatId }, // Placeholder for actual beat names
            selectedTakes: selected,
            removals: removals,
            beforeDurationMs: projectDuration,
            afterDurationMs: max(0, projectDuration - removedMs),
            evidenceIds: evidenceIds,
            assumptions: assumptions,
            warnings: warnings
        )
    }
    
    private static func comparison(group: TakeGroup) -> TakeComparison {
        return TakeComparison(
            groupId: group.id,
            sentence: group.takes.first?.transcript ?? "",
            selectedTakeId: group.bestTakeId,
            alternatives: group.takes.filter { $0.id != group.bestTakeId }.map { $0.id },
            explanation: "", // No explanation array in iOS TakeGroup currently
            confidence: 1.0 // No confidence float in iOS TakeGroup currently
        )
    }
    
    private static func mergeDuration(ranges: [TimeRange]) -> Int64 {
        if ranges.isEmpty { return 0 }
        let sorted = ranges.sorted { $0.startMs < $1.startMs }
        var start = sorted.first!.startMs
        var end = sorted.first!.endMs
        var total: Int64 = 0
        
        for range in sorted.dropFirst() {
            if range.startMs <= end {
                end = max(end, range.endMs)
            } else {
                total += (end - start)
                start = range.startMs
                end = range.endMs
            }
        }
        return total + (end - start)
    }
    
    private static func percent(_ value: Float) -> String {
        return "\(Int(round(value * 100)))%"
    }
    
    private static func formatRange(_ range: TimeRange) -> String {
        return "\(formatTime(range.startMs))–\(formatTime(range.endMs))"
    }
    
    private static func formatTime(_ ms: Int64) -> String {
        return String(format: "%d:%02d", ms / 60_000, (ms / 1_000) % 60)
    }
}
