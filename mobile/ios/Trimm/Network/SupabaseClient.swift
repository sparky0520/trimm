import Foundation
import Supabase

public let supabaseURL = URL(string: Secrets.supabaseURL)!
public let supabaseKey = Secrets.supabasePublishableKey

public let supabase = SupabaseClient(
    supabaseURL: supabaseURL,
    supabaseKey: supabaseKey,
    options: SupabaseClientOptions(
        auth: SupabaseClientOptions.AuthOptions(
            emitLocalSessionAsInitialSession: true
        )
    )
)
