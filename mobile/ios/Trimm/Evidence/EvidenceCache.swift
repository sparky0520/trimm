import Foundation

public class LocalEvidenceCache: EvidenceCache {
    public let root: URL
    
    public init(root: URL) {
        self.root = root
    }
    
    public var artifactDirectory: URL {
        return root
    }
    
    public func artifactDirectory(for source: SourceMedia, analyzer: AnalyzerIdentity) -> URL {
        let dir = root
            .appendingPathComponent(source.fingerprint)
            .appendingPathComponent("\(safe(analyzer.name))-\(safe(analyzer.version))")
            .appendingPathComponent("artifacts")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
    
    public func readAnalyzer(source: SourceMedia, identity: AnalyzerIdentity) async throws -> [AnyEvidenceObservation]? {
        let file = analyzerFile(source: source, analyzer: identity)
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        let data = try Data(contentsOf: file)
        let decoder = JSONDecoder()
        return try? decoder.decode([AnyEvidenceObservation].self, from: data)
    }
    
    public func writeAnalyzer(source: SourceMedia, identity: AnalyzerIdentity, observations: [AnyEvidenceObservation]) async throws {
        let file = analyzerFile(source: source, analyzer: identity)
        let encoder = JSONEncoder()
        let data = try encoder.encode(observations)
        try atomicWrite(target: file, data: data)
    }
    
    public func writeGraph(graph: EvidenceGraph) async throws {
        let file = root.appendingPathComponent(graph.sourceFingerprint).appendingPathComponent("graph-v\(graph.schemaVersion).json")
        let encoder = JSONEncoder()
        let data = try encoder.encode(graph)
        try atomicWrite(target: file, data: data)
    }
    
    public func readGraph(sourceFingerprint: String) -> EvidenceGraph? {
        let file = root.appendingPathComponent(sourceFingerprint).appendingPathComponent("graph-v1.json")
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        guard let data = try? Data(contentsOf: file) else { return nil }
        let decoder = JSONDecoder()
        return try? decoder.decode(EvidenceGraph.self, from: data)
    }
    
    private func analyzerFile(source: SourceMedia, analyzer: AnalyzerIdentity) -> URL {
        return root
            .appendingPathComponent(source.fingerprint)
            .appendingPathComponent("\(safe(analyzer.name))-\(safe(analyzer.version))")
            .appendingPathComponent("observations.json")
    }
    
    private func atomicWrite(target: URL, data: Data) throws {
        let parentDir = target.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: parentDir, withIntermediateDirectories: true)
        
        let temporary = parentDir.appendingPathComponent("\(target.lastPathComponent).partial")
        try data.write(to: temporary)
        
        _ = try FileManager.default.replaceItemAt(target, withItemAt: temporary)
    }
    
    private func safe(_ value: String) -> String {
        let regex = try! NSRegularExpression(pattern: "[^a-z0-9._-]", options: .caseInsensitive)
        let range = NSRange(location: 0, length: value.utf16.count)
        return regex.stringByReplacingMatches(in: value.lowercased(), options: [], range: range, withTemplate: "-")
    }
}
