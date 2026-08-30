import Foundation
import Supabase

public let supabaseURL = URL(string: Bundle.main.object(forInfoDictionaryKey: "SupabaseURL") as? String ?? "https://YOUR_SUPABASE_PROJECT_REF.supabase.co")!
public let supabaseKey = Bundle.main.object(forInfoDictionaryKey: "SupabaseKey") as? String ?? "YOUR_SUPABASE_ANON_KEY"

public let supabase = SupabaseClient(
    supabaseURL: supabaseURL,
    supabaseKey: supabaseKey
)
