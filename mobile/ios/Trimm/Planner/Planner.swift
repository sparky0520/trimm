import Foundation

public class ChatCompletionCoordinator {
    private let gateway: AgentRunGateway
    private let allowedActivityTypes: Set<String> = [
        "started", "transcript_received", "analyzing", "evidence_requested", "evidence_received",
        "proposal_repairing", "proposal_repaired", "proposal_repair_failed",
        "clarification", "proposal_ready", "partial", "failed", "cancelled"
    ]
    
    public init(gateway: AgentRunGateway) {
        self.gateway = gateway
    }
    
    public func orchestrateStateless(
        request: AgentRunRequest,
        projectDurationMs: Int64,
        onProgress: @escaping (String, Int) async -> Void,
        onPersist: @escaping (PersistedAgentRun, AgentPlanResponse?) async -> Void
    ) async throws -> PersistedAgentRun {
        
        var snapshot = PersistedAgentRun(
            runId: UUID().uuidString,
            projectId: request.projectId,
            projectRevision: request.projectRevision,
            status: .partial,
            events: [
                AgentActivityEvent(sequence: 1, type: "started", message: "Starting an orchestrated video edit.", createdAt: ISO8601DateFormatter().string(from: Date()))
            ],
            decision: ["type": AnyCodable("fallback"), "reason": AnyCodable("Started")]
        )
        
        await onPersist(snapshot, nil)
        await onProgress("agent-task: Executing user request", 50)
        
        var taskAttempt = 0
        while taskAttempt < 3 {
            do {
                let outcome = try await gateway.chatCompletion(request: request)
                
                let outcomeType = (outcome["type"]?.value as? String) ?? ""
                
                if outcomeType == "edit_program" {
                    var finalOperations = request.existingOperations
                    // Mapping existing operations is simplified here.
                    
                    var newOps = [[String: AnyCodable]]()
                    if let rawOps = outcome["operations"]?.value as? [[String: Any]] {
                        for var rawOp in rawOps {
                            // Extract specific properties to see if it requires bgm/sfx generation.
                            // Assuming `rawOp` dictionary keys map directly
                            if let type = rawOp["type"] as? String {
                                if type == "add_bgm", let url = rawOp["url"] as? String, url.isEmpty {
                                    await onProgress("Generating background music...", 75)
                                    // Dummy logic for calculating duration based on target range
                                    let durationMs: Float = Float(projectDurationMs) / 1000.0
                                    let prompt = (rawOp["prompt"] as? String) ?? ""
                                    let generatedUrl = try await gateway.generateBgm(prompt: prompt, durationSecs: durationMs, isEffect: false, chatId: request.projectId)
                                    rawOp["url"] = generatedUrl
                                } else if type == "add_bgm_effect", let url = rawOp["url"] as? String, url.isEmpty {
                                    await onProgress("Generating music effect...", 75)
                                    let prompt = (rawOp["prompt"] as? String) ?? ""
                                    let generatedUrl = try await gateway.generateBgm(prompt: prompt, durationSecs: 12.0, isEffect: true, chatId: request.projectId)
                                    rawOp["url"] = generatedUrl
                                } else if type == "add_sfx", let url = rawOp["url"] as? String, url.isEmpty {
                                    await onProgress("Generating sound effect...", 75)
                                    let prompt = (rawOp["prompt"] as? String) ?? ""
                                    let generatedUrl = try await gateway.generateSfx(prompt: prompt)
                                    rawOp["url"] = generatedUrl
                                }
                            }
                            
                            var anyOp = [String: AnyCodable]()
                            for (k, v) in rawOp {
                                anyOp[k] = AnyCodable(v)
                            }
                            newOps.append(anyOp)
                        }
                    }
                    
                    let newOpIds = Set(newOps.compactMap { $0["id"]?.value as? String })
                    let removedOpIds = Set((outcome["removedOperations"]?.value as? [String]) ?? [])
                    
                    finalOperations = finalOperations.filter { existingOp in
                        return !newOpIds.contains(existingOp.id) && !removedOpIds.contains(existingOp.id)
                    }
                    
                    do {
                        let data = try JSONEncoder().encode(newOps)
                        let decodedNewOps = try JSONDecoder().decode([EditOperation].self, from: data)
                        finalOperations.append(contentsOf: decodedNewOps)
                    } catch {
                        print("Planner: Failed to decode new operations - \(error)")
                    }
                    
                    let summaryStr = (outcome["summary"]?.value as? String) ?? "Proposed edit"
                    let msg = newOps.isEmpty ? "Completed task. (No edits needed)" : "Completed task."
                    
                    var decisionMap = [String: AnyCodable]()
                    decisionMap["type"] = AnyCodable("edit_program")
                    
                    do {
                        let data = try JSONEncoder().encode(finalOperations)
                        let opsArray = try JSONDecoder().decode([[String: AnyCodable]].self, from: data)
                        decisionMap["operations"] = AnyCodable(opsArray)
                    } catch {
                        decisionMap["operations"] = AnyCodable([])
                    }
                    
                    decisionMap["expectedRevision"] = AnyCodable(request.projectRevision)
                    decisionMap["summary"] = AnyCodable(summaryStr)
                    
                    var newEvents = snapshot.events
                    newEvents.append(AgentActivityEvent(sequence: newEvents.count + 1, type: "proposal_ready", message: msg, createdAt: ISO8601DateFormatter().string(from: Date())))
                    
                    snapshot = PersistedAgentRun(
                        runId: snapshot.runId,
                        projectId: snapshot.projectId,
                        projectRevision: snapshot.projectRevision,
                        status: .proposalReady,
                        events: newEvents,
                        decision: decisionMap
                    )
                } else if outcomeType == "clarification" {
                    var newEvents = snapshot.events
                    newEvents.append(AgentActivityEvent(sequence: newEvents.count + 1, type: "clarification", message: "Task requested clarification", createdAt: ISO8601DateFormatter().string(from: Date())))
                    
                    snapshot = PersistedAgentRun(
                        runId: snapshot.runId,
                        projectId: snapshot.projectId,
                        projectRevision: snapshot.projectRevision,
                        status: .waitingForUser,
                        events: newEvents,
                        decision: outcome
                    )
                } else if outcomeType == "fallback" {
                    throw NSError(domain: "ChatCompletion", code: 0, userInfo: [NSLocalizedDescriptionKey: (outcome["reason"]?.value as? String) ?? "Task failed."])
                }
                
                await onPersist(snapshot, nil)
                break
                
            } catch {
                taskAttempt += 1
                if taskAttempt == 3 {
                    var newEvents = snapshot.events
                    newEvents.append(AgentActivityEvent(sequence: newEvents.count + 1, type: "failed", message: "Task failed: \(error.localizedDescription)", createdAt: ISO8601DateFormatter().string(from: Date())))
                    
                    snapshot = PersistedAgentRun(
                        runId: snapshot.runId,
                        projectId: snapshot.projectId,
                        projectRevision: snapshot.projectRevision,
                        status: .failed,
                        events: newEvents,
                        decision: snapshot.decision
                    )
                    await onPersist(snapshot, nil)
                    throw error
                }
                try? await Task.sleep(nanoseconds: UInt64(2000 * taskAttempt) * 1_000_000_000)
            }
        }
        
        await onProgress("agent-run-complete", 100)
        return snapshot
    }
}
