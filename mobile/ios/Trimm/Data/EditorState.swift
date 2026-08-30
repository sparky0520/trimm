import Foundation

public struct EditorState {
    public var sources: [SourceMedia] = []
    public var preferences: WizardPreferences = WizardPreferences()
    public var project: MobileProject? = nil
    
    public var capabilityMessage: String? = nil
    public var previewUri: String? = nil
    public var jobMessage: String? = nil
    public var errorMessage: String? = nil
    public var initialRequest: String? = nil
    public var pendingChatMessage: String? = nil
    public var isProcessing: Bool = false
    public var pastChats: [MobileProject] = []
    
    public var source: SourceMedia? {
        sources.first
    }
}
