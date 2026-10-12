import Foundation
import Observation
import StoreKit
import RevenueCat
import Supabase

public struct CreditPackage: Identifiable, Equatable {
    public let id: String
    public let name: String
    public let credits: Int
    public var priceString: String
    public let badge: String?
    public let description: String
    public var rcPackage: Package?
    
    public static func == (lhs: CreditPackage, rhs: CreditPackage) -> Bool {
        return lhs.id == rhs.id && lhs.priceString == rhs.priceString
    }
}

public struct CreditCostConfig: Codable, Equatable {
    public let CHAT_COMPLETION: Int?
    public let BGM_GENERATION: Int?
    public let TRANSCRIPTION: Int?
    public let SFX_FETCH: Int?
    
    public var chatCost: Int { CHAT_COMPLETION ?? 2 }
    public var bgmCost: Int { BGM_GENERATION ?? 5 }
    public var transcriptionCost: Int { TRANSCRIPTION ?? 3 }
    public var sfxCost: Int { SFX_FETCH ?? 1 }
}

public struct CreditBalanceResponse: Codable {
    public let userId: String?
    public let balance: Int
    public let lifetimePurchased: Int?
    public let lifetimeSpent: Int?
    public let costs: CreditCostConfig?
}

public struct CreditTransactionItem: Identifiable, Codable, Equatable {
    public let id: String
    public let amount: Int
    public let balance_after: Int
    public let type: String
    public let description: String?
    public let created_at: String
}

private struct TransactionHistoryResponse: Codable {
    let transactions: [CreditTransactionItem]
}

@Observable
@MainActor
public class BillingManager {
    public static let shared = BillingManager()
    
    public var balance: Int = 0
    public var lifetimePurchased: Int = 0
    public var lifetimeSpent: Int = 0
    public var costs: CreditCostConfig = CreditCostConfig(CHAT_COMPLETION: 2, BGM_GENERATION: 5, TRANSCRIPTION: 3, SFX_FETCH: 1)
    public var isLoading: Bool = false
    public var isPurchasing: Bool = false
    public var errorMessage: String? = nil
    public var packages: [CreditPackage] = []
    public var transactions: [CreditTransactionItem] = []
    public var isStorePresented: Bool = false
    
    private var isConfigured = false
    private let baseURL = URL(string: "\(Secrets.backendURL)/v1")!
    
    public init() {
        setupDefaultPackages()
    }
    
    private func setupDefaultPackages() {
        self.packages = [
            CreditPackage(
                id: Secrets.creditPackage100,
                name: "Starter",
                credits: 100,
                priceString: "$4.99",
                badge: nil,
                description: "50 AI edits or ~33 video transcriptions",
                rcPackage: nil
            ),
            CreditPackage(
                id: Secrets.creditPackage300,
                name: "Creator",
                credits: 300,
                priceString: "$11.99",
                badge: "MOST POPULAR",
                description: "150 AI edits with transcriptions & projects",
                rcPackage: nil
            ),
            CreditPackage(
                id: Secrets.creditPackage1000,
                name: "Pro Studio",
                credits: 1000,
                priceString: "$29.99",
                badge: "BEST VALUE",
                description: "500 AI edits with priority processing",
                rcPackage: nil
            )
        ]
    }
    
    public func configure() {
        guard !isConfigured else { return }
        
        let apiKey = Secrets.revenueCatApiKey
        if !apiKey.isEmpty && !apiKey.contains("PLACEHOLDER") {
            Purchases.logLevel = .warn
            Purchases.configure(withAPIKey: apiKey)
            print("[BillingManager] RevenueCat configured successfully.")
        } else {
            print("[BillingManager] RevenueCat running in development/local mode with default packages.")
        }
        
        isConfigured = true
        Task {
            await fetchOfferings()
        }
    }
    
    public func updateUser(userId: String?) async {
        if let uid = userId, !uid.isEmpty {
            if Purchases.isConfigured {
                _ = try? await Purchases.shared.logIn(uid)
            }
            await fetchCredits()
            await fetchOfferings()
        } else {
            if Purchases.isConfigured {
                _ = try? await Purchases.shared.logOut()
            }
            self.balance = 0
            self.transactions = []
        }
    }
    
    public func fetchCredits() async {
        guard let session = try? await supabase.auth.session else { return }
        
        var request = URLRequest(url: baseURL.appendingPathComponent("billing/credits"))
        request.httpMethod = "GET"
        request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
                return
            }
            let res = try JSONDecoder().decode(CreditBalanceResponse.self, from: data)
            self.balance = res.balance
            self.lifetimePurchased = res.lifetimePurchased ?? 0
            self.lifetimeSpent = res.lifetimeSpent ?? 0
            if let c = res.costs {
                self.costs = c
            }
        } catch {
            print("[BillingManager] fetchCredits failed: \(error)")
        }
    }
    
    public func fetchHistory() async {
        guard let session = try? await supabase.auth.session else { return }
        
        var request = URLRequest(url: baseURL.appendingPathComponent("billing/history"))
        request.httpMethod = "GET"
        request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
                return
            }
            let res = try JSONDecoder().decode(TransactionHistoryResponse.self, from: data)
            self.transactions = res.transactions
        } catch {
            print("[BillingManager] fetchHistory failed: \(error)")
        }
    }
    
    public func fetchOfferings() async {
        guard Purchases.isConfigured else { return }
        
        do {
            let offerings = try await Purchases.shared.offerings()
            guard let current = offerings.current else { return }
            
            var updatedPackages: [CreditPackage] = []
            for pkg in current.availablePackages {
                let id = pkg.storeProduct.productIdentifier
                let credits: Int
                let name: String
                let badge: String?
                let desc: String
                
                if id.contains("1000") {
                    credits = 1000
                    name = "Pro Studio"
                    badge = "BEST VALUE"
                    desc = "500 AI edits with priority processing"
                } else if id.contains("300") {
                    credits = 300
                    name = "Creator"
                    badge = "MOST POPULAR"
                    desc = "150 AI edits with transcriptions & projects"
                } else {
                    credits = 100
                    name = "Starter"
                    badge = nil
                    desc = "50 AI edits or ~33 video transcriptions"
                }
                
                updatedPackages.append(CreditPackage(
                    id: id,
                    name: name,
                    credits: credits,
                    priceString: pkg.localizedPriceString,
                    badge: badge,
                    description: desc,
                    rcPackage: pkg
                ))
            }
            
            if !updatedPackages.isEmpty {
                self.packages = updatedPackages.sorted { $0.credits < $1.credits }
            }
        } catch {
            print("[BillingManager] fetchOfferings failed: \(error)")
        }
    }
    
    public func purchase(package: CreditPackage) async throws {
        isPurchasing = true
        errorMessage = nil
        defer { isPurchasing = false }
        
        if Purchases.isConfigured, let rcPkg = package.rcPackage {
            do {
                let result = try await Purchases.shared.purchase(package: rcPkg)
                if !result.userCancelled {
                    // Small delay to allow RevenueCat webhook or backend to update Supabase
                    try? await Task.sleep(nanoseconds: 800_000_000)
                    await fetchCredits()
                    await fetchHistory()
                }
            } catch {
                if let rcError = error as? RevenueCat.ErrorCode, rcError == .purchaseCancelledError {
                    return
                }
                self.errorMessage = error.localizedDescription
                throw error
            }
        } else {
            // Local fallback / Developer mode: simulate purchase and refresh credits
            print("[BillingManager] Local sandbox purchase simulated for \(package.name)")
            try? await Task.sleep(nanoseconds: 500_000_000)
            await fetchCredits()
        }
    }
    
    public func restorePurchases() async {
        guard Purchases.isConfigured else { return }
        isLoading = true
        defer { isLoading = false }
        
        do {
            _ = try await Purchases.shared.restorePurchases()
            await fetchCredits()
        } catch {
            self.errorMessage = error.localizedDescription
        }
    }
}
