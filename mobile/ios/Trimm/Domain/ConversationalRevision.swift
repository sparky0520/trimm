import Foundation

public enum EditorialConstraintKind: String, Codable {
    case takeSelection = "TAKE_SELECTION"
    case openingPace = "OPENING_PACE"
    case privacy = "PRIVACY"
    case effectDensity = "EFFECT_DENSITY"
}

public struct SettledEditorialConstraint: Codable, Equatable {
    public let kind: EditorialConstraintKind
    public let value: String
    public let sourceTurnId: String
    
    public init(kind: EditorialConstraintKind, value: String, sourceTurnId: String) {
        self.kind = kind
        self.value = value
        self.sourceTurnId = sourceTurnId
    }
}

public struct EditorialConversationTurn: Codable, Equatable {
    public let id: String
    public let parentId: String?
    public let request: String
    public let response: String
    public let evidenceIds: [String]
    public let isPending: Bool
    public let isError: Bool
    public let createdAtEpochMs: Int64
    
    public init(id: String = UUID().uuidString, parentId: String? = nil, request: String, response: String, evidenceIds: [String] = [], isPending: Bool = false, isError: Bool = false, createdAtEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        self.id = id
        self.parentId = parentId
        self.request = request
        self.response = response
        self.evidenceIds = evidenceIds
        self.isPending = isPending
        self.isError = isError
        self.createdAtEpochMs = createdAtEpochMs
    }
}

public enum ConversationalRevisionResult {
    case answer(project: MobileProject, text: String, evidenceIds: [String])
    case revision(project: MobileProject, proposal: PendingEditorialProposal)
    case clarification(project: MobileProject, question: String)
}

public enum ConversationalRevisionEngine {
    public static func revise(project: MobileProject, request: String) -> ConversationalRevisionResult {
        let text = request.trimmingCharacters(in: .whitespacesAndNewlines)
        precondition(!text.isEmpty && text.count <= 2000)
        let normalized = text.lowercased()
        
        if normalized.hasPrefix("why") || normalized.contains("why did") {
            return answerWhy(project: project, request: text)
        } else if normalized.contains("other take") {
            return useOtherTake(project: project, request: text)
        } else {
            return clarification(project: project, request: text, question: "What part of the current story should I revise?")
        }
    }
    
    private static func answerWhy(project: MobileProject, request: String) -> ConversationalRevisionResult {
        let proposal = project.analysis.pendingProposal
        let plan = proposal?.plan ?? project.analysis.editorialPlan
        
        var explanations = [String]()
        plan?.story.forEach { explanations.append($0.rationale) }
        plan?.decisions.forEach { explanations.append($0.rationale) }
        plan?.operationPlans.forEach { explanations.append($0.rationale) }
        
        var evidence = [String]()
        if let p = proposal { evidence.append(contentsOf: p.evidenceIds) }
        plan?.story.forEach { evidence.append(contentsOf: $0.evidenceIds) }
        plan?.decisions.forEach { evidence.append(contentsOf: $0.evidenceIds) }
        plan?.operationPlans.forEach { evidence.append(contentsOf: $0.evidenceIds) }
        
        let uniqueExplanations = Array(NSOrderedSet(array: explanations)) as! [String]
        let uniqueEvidence = Array(NSOrderedSet(array: evidence)) as! [String]
        
        let response = !uniqueExplanations.isEmpty ? uniqueExplanations.joined(separator: " ") : "There is no recorded editorial decision to explain yet."
        let updated = appendTurn(project: project, request: request, response: response, evidenceIds: uniqueEvidence)
        return .answer(project: updated, text: response, evidenceIds: uniqueEvidence)
    }
    
    private static func useOtherTake(project: MobileProject, request: String) -> ConversationalRevisionResult {
        guard let current = project.analysis.pendingProposal else {
            return clarification(project: project, request: request, question: "There is no current proposal to revise.")
        }
        
        let choices = current.plan.story.map { choice -> StoryPlanChoice in
            let group = project.analysis.takeGroups.first { group in
                group.takes.contains { $0.id == choice.selectedTakeId }
            }
            // using group.takes instead of group.alternativeTakeIds and group.candidates as evidence format differs slightly
            let other = group?.takes.first { $0.id != choice.selectedTakeId }?.id
            
            if let other = other {
                return StoryPlanChoice(
                    beatId: choice.beatId,
                    selectedTakeId: other,
                    rationale: "The user explicitly requested the other known take.",
                    evidenceIds: [], // Placeholder, map actual take evidence here if available
                    confidence: 1.0,
                    assumptions: [],
                    expectedStoryEffect: "Keeps the same story beat while changing only its delivery."
                )
            }
            return choice
        }
        
        if choices == current.plan.story {
            return clarification(project: project, request: request, question: "Which story beat should use another take?")
        }
        
        let selected = Set(choices.compactMap { $0.selectedTakeId })
        var alternateRanges = [TimeRange]()
        for group in project.analysis.takeGroups {
            alternateRanges.append(contentsOf: group.takes.filter { !selected.contains($0.id) }.map { $0.range })
        }
        
        let operations = current.operations.map { operation -> EditOperation in
            if case .removeRanges(let id, let enabled, let ranges, let confidence, let reason, let pacing) = operation {
                let uniqueRanges = Array(NSOrderedSet(array: ranges + alternateRanges)) as! [TimeRange]
                let sorted = uniqueRanges.sorted { $0.startMs < $1.startMs }
                return .removeRanges(id: id, enabled: enabled, ranges: sorted, confidence: confidence, reason: "Superseded alternate take", pacing: pacing)
            }
            return operation
        }
        
        let constraint = SettledEditorialConstraint(
            kind: .takeSelection,
            value: selected.sorted().joined(separator: ","),
            sourceTurnId: ""
        )
        
        let newPlan = StagedEditorialPlan(story: choices, decisions: current.plan.decisions, operationPlans: current.plan.operationPlans)
        
        var newEvidence = current.evidenceIds
        choices.forEach { newEvidence.append(contentsOf: $0.evidenceIds) }
        let uniqueNewEvidence = Array(NSOrderedSet(array: newEvidence)) as! [String]
        
        let newProposal = PendingEditorialProposal(
            expectedRevision: project.revision,
            summary: "Use the other take without changing the rest of the story",
            plan: newPlan,
            operations: operations,
            evidenceIds: uniqueNewEvidence
        )
        
        return replaceProposal(project: project, request: request, proposal: newProposal, constraint: constraint)
    }
    
    private static func scopedRevision(
        project: MobileProject,
        request: String,
        kind: EditorialConstraintKind,
        value: String,
        transform: ([EditOperation]) -> [EditOperation]
    ) -> ConversationalRevisionResult {
        guard let current = project.analysis.pendingProposal else {
            return clarification(project: project, request: request, question: "There is no current proposal to revise.")
        }
        
        let operations = transform(current.operations)
        
        let summary: String
        switch kind {
        case .openingPace: summary = "Open faster while preserving the settled story"
        case .privacy: summary = "Preserve privacy throughout the current story"
        case .effectDensity: summary = "Use fewer effects without changing story timing"
        case .takeSelection: summary = current.summary
        }
        
        let filteredPlans = current.plan.operationPlans.filter { planned in
            operations.contains { $0.id == planned.operationId }
        }
        let newPlan = StagedEditorialPlan(story: current.plan.story, decisions: current.plan.decisions, operationPlans: filteredPlans)
        
        let proposal = PendingEditorialProposal(
            expectedRevision: project.revision,
            summary: summary,
            plan: newPlan,
            operations: operations,
            evidenceIds: current.evidenceIds
        )
        
        return replaceProposal(project: project, request: request, proposal: proposal, constraint: SettledEditorialConstraint(kind: kind, value: value, sourceTurnId: ""))
    }
    
    private static func replaceProposal(
        project: MobileProject,
        request: String,
        proposal: PendingEditorialProposal,
        constraint: SettledEditorialConstraint
    ) -> ConversationalRevisionResult {
        let turn = EditorialConversationTurn(request: request, response: proposal.summary, evidenceIds: proposal.evidenceIds)
        var settled = project.analysis.settledConstraints.filter { $0.kind != constraint.kind }
        settled.append(SettledEditorialConstraint(kind: constraint.kind, value: constraint.value, sourceTurnId: turn.id))
        
        var newHistory = project.analysis.chatHistory + [turn]
        if newHistory.count > 100 { newHistory = Array(newHistory.suffix(100)) }
        
        let newAnalysis = Analysis(
            words: project.analysis.words,
            captions: project.analysis.captions,
            speechHints: project.analysis.speechHints,
            lowConfidenceFumbles: project.analysis.lowConfidenceFumbles,
            takeGroups: project.analysis.takeGroups,
            warnings: project.analysis.warnings,
            completedAtEpochMs: project.analysis.completedAtEpochMs,
            activeTurnId: project.analysis.activeTurnId,
            activeCheckpointId: project.analysis.activeCheckpointId,
            editorialPlan: project.analysis.editorialPlan,
            pendingProposal: proposal,
            chatHistory: newHistory,
            settledConstraints: settled
        )
        
        let updated = MobileProject(
            schemaVersion: project.schemaVersion, id: project.id, title: project.title,
            source: project.source, sources: project.sources, proxyUri: project.proxyUri,
            preferences: project.preferences, analysis: newAnalysis,
            evidenceGraphs: project.evidenceGraphs, operations: project.operations,
            undo: project.undo, redo: project.redo, export: project.export, revision: project.revision
        )
        
        return .revision(project: updated, proposal: proposal)
    }
    
    private static func clarification(project: MobileProject, request: String, question: String) -> ConversationalRevisionResult {
        let updated = appendTurn(project: project, request: request, response: question, evidenceIds: [])
        return .clarification(project: updated, question: question)
    }
    
    private static func appendTurn(project: MobileProject, request: String, response: String, evidenceIds: [String]) -> MobileProject {
        let turn = EditorialConversationTurn(request: request, response: response, evidenceIds: evidenceIds)
        var newHistory = project.analysis.chatHistory + [turn]
        if newHistory.count > 100 { newHistory = Array(newHistory.suffix(100)) }
        
        let newAnalysis = Analysis(
            words: project.analysis.words,
            captions: project.analysis.captions,
            speechHints: project.analysis.speechHints,
            lowConfidenceFumbles: project.analysis.lowConfidenceFumbles,
            takeGroups: project.analysis.takeGroups,
            warnings: project.analysis.warnings,
            completedAtEpochMs: project.analysis.completedAtEpochMs,
            activeTurnId: project.analysis.activeTurnId,
            activeCheckpointId: project.analysis.activeCheckpointId,
            editorialPlan: project.analysis.editorialPlan,
            pendingProposal: project.analysis.pendingProposal,
            chatHistory: newHistory,
            settledConstraints: project.analysis.settledConstraints
        )
        
        return MobileProject(
            schemaVersion: project.schemaVersion, id: project.id, title: project.title,
            source: project.source, sources: project.sources, proxyUri: project.proxyUri,
            preferences: project.preferences, analysis: newAnalysis,
            evidenceGraphs: project.evidenceGraphs, operations: project.operations,
            undo: project.undo, redo: project.redo, export: project.export, revision: project.revision
        )
    }
}
