import Foundation

public struct ChatMessage: Identifiable, Equatable {
    public let id = UUID()
    public let text: String
    public let isUser: Bool
    
    public init(text: String, isUser: Bool) {
        self.text = text
        self.isUser = isUser
    }
}

public struct EditorState {
    public var sources: [SourceMedia] = []
    public var preferences: WizardPreferences = WizardPreferences()
    public var project: MobileProject? = nil
    public var compositionSchema: CompositionSchema? = nil
    
    public var capabilityMessage: String? = nil
    public var previewUri: String? = nil
    public var jobMessage: String? = nil
    public var errorMessage: String? = nil
    public var initialRequest: String? = nil
    public var pendingChatMessage: String? = nil
    public var isProcessing: Bool = false
    public var isExporting: Bool = false
    public var exportProgress: Float = 0.0
    public var isLoadingPastChats: Bool = false
    public var pastChats: [ChatSummary] = []
    public var messages: [ChatMessage] = []
    
    public var source: SourceMedia? {
        sources.first
    }
}
