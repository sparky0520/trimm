import Foundation

/// Central repository for all Trimm client-side secrets, endpoints, API keys, and store identifiers.
public enum Secrets {
    // MARK: - Backend & Cloud
    public static let backendURL = "https://trimm.fly.dev"
    
    // MARK: - Supabase
    public static let supabaseURL = "https://gahrfsmpnakrjmvhikzf.supabase.co"
    public static let supabasePublishableKey = "sb_publishable_7xsS4O5zgZlvfkS1bhAgLQ_kXkfTz92"
    
    // MARK: - Google Sign-In
    public static let googleIosClientId = "781659307868-19r9atulmptqncgqnsm08ttin94l850p.apps.googleusercontent.com"
    public static let googleWebClientId = "781659307868-b58g4qtacl6hjvdfs19nel896kbdomm8.apps.googleusercontent.com"
    
    // MARK: - RevenueCat
    /// Public Apple API key from RevenueCat Dashboard -> Project Settings -> API Keys (starts with 'appl_')
    public static let revenueCatApiKey = "appl_YOUR_ACTUAL_KEY_HERE"
    
    // MARK: - In-App Purchase Product Identifiers (App Store Connect & RevenueCat)
    public static let creditPackage100 = "ai.ctrlb.credits.100"
    public static let creditPackage300 = "ai.ctrlb.credits.300"
    public static let creditPackage1000 = "ai.ctrlb.credits.1000"
    public static let defaultOfferingId = "default"
}
