import Foundation

public struct AnalyzerResult {
    public let observations: [AnyEvidenceObservation]
    public let run: AnalyzerRun
}

public protocol EvidenceCache {
    var artifactDirectory: URL { get }
    func readAnalyzer(source: SourceMedia, identity: AnalyzerIdentity) async throws -> [AnyEvidenceObservation]?
    func writeAnalyzer(source: SourceMedia, identity: AnalyzerIdentity, observations: [AnyEvidenceObservation]) async throws
    func writeGraph(graph: EvidenceGraph) async throws
}

public class EvidencePipeline {
    private let cache: EvidenceCache
    private let analyzers: [EvidenceAnalyzer]
    private let clock: () -> Int64
    
    public init(cache: EvidenceCache, analyzers: [EvidenceAnalyzer], clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.cache = cache
        self.analyzers = analyzers
        self.clock = clock
    }
    
    public func analyze(source: SourceMedia) async throws -> EvidenceGraph {
        return try await withThrowingTaskGroup(of: AnalyzerResult.self) { group in
            for analyzer in analyzers {
                group.addTask {
                    if let cached = try? await self.cache.readAnalyzer(source: source, identity: analyzer.identity) {
                        var unique: [AnyEvidenceObservation] = []
                        var seenIds = Set<String>()
                        for obs in cached {
                            if !seenIds.contains(obs.base.id) {
                                seenIds.insert(obs.base.id)
                                unique.append(obs)
                            }
                        }
                        return AnalyzerResult(observations: unique, run: AnalyzerRun(analyzer: analyzer.identity, status: .cached, startedAtEpochMs: self.clock(), completedAtEpochMs: self.clock(), observationCount: unique.count))
                    }
                    
                    let started = self.clock()
                    do {
                        // For a real implementation, artifactDirectory would be specific to source and analyzer identity
                        let analyzerDir = self.cache.artifactDirectory.appendingPathComponent(source.fingerprint).appendingPathComponent(analyzer.identity.name)
                        try? FileManager.default.createDirectory(at: analyzerDir, withIntermediateDirectories: true)
                        
                        let context = AnalyzerContext(source: source, artifactDirectory: analyzerDir)
                        let observations = try await analyzer.analyze(context: context)
                        
                        var unique: [AnyEvidenceObservation] = []
                        var seenIds = Set<String>()
                        for obs in observations {
                            if !seenIds.contains(obs.base.id) {
                                seenIds.insert(obs.base.id)
                                unique.append(obs)
                            }
                        }
                        
                        for obs in unique {
                            precondition(obs.base.sourceFingerprint == source.fingerprint)
                        }
                        
                        try? await self.cache.writeAnalyzer(source: source, identity: analyzer.identity, observations: unique)
                        
                        return AnalyzerResult(observations: unique, run: AnalyzerRun(analyzer: analyzer.identity, status: .complete, startedAtEpochMs: started, completedAtEpochMs: self.clock(), observationCount: unique.count))
                    } catch {
                        let status: AnalyzerStatus = (error as NSError).code == 9999 ? .unsupported : .failed // example checking
                        let safeError = String(error.localizedDescription.prefix(240))
                        return AnalyzerResult(observations: [], run: AnalyzerRun(analyzer: analyzer.identity, status: status, startedAtEpochMs: started, completedAtEpochMs: self.clock(), observationCount: 0, safeError: safeError))
                    }
                }
            }
            
            var results: [AnalyzerResult] = []
            for try await result in group {
                results.append(result)
            }
            
            var allObservations: [AnyEvidenceObservation] = []
            for res in results {
                allObservations.append(contentsOf: res.observations)
            }
            
            var uniqueObservations: [AnyEvidenceObservation] = []
            var seenIds = Set<String>()
            for obs in allObservations {
                if !seenIds.contains(obs.base.id) {
                    seenIds.insert(obs.base.id)
                    uniqueObservations.append(obs)
                }
            }
            
            uniqueObservations.sort {
                if $0.base.range.startMs == $1.base.range.startMs {
                    return $0.base.id < $1.base.id
                }
                return $0.base.range.startMs < $1.base.range.startMs
            }
            
            let runs = results.map { $0.run }
            
            let localAnalyzers = analyzers.filter { $0.identity.execution == .localDevice }.map { $0.identity.name }.sorted()
            let externalAnalyzers = analyzers.filter { $0.identity.execution == .externalService }.map {
                ExternalDisclosure(service: $0.identity.name, data: "audio track with source timestamps", purpose: "speech transcription and speaker evidence")
            }
            
            let disclosure = EvidenceDisclosure(computedLocally: localAnalyzers, sentExternally: externalAnalyzers)
            
            let graph = EvidenceGraph(
                schemaVersion: 1,
                sourceFingerprint: source.fingerprint,
                sourceDurationMs: source.durationMs,
                createdAtEpochMs: self.clock(),
                observations: uniqueObservations,
                analyzerRuns: runs,
                disclosure: disclosure
            )
            
            try? await self.cache.writeGraph(graph: graph)
            return graph
        }
    }
}
