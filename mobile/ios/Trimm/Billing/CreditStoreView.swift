import SwiftUI

public struct CreditStoreView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var billing = BillingManager.shared
    @State private var selectedPackageId: String = Secrets.creditPackage300
    @State private var showingPurchaseSuccess = false
    @State private var purchasedCreditsCount = 0
    @State private var purchaseError: String? = nil
    
    public init() {}
    
    public var body: some View {
        NavigationStack {
            ZStack {
                Color.ink.ignoresSafeArea()
                
                ScrollView {
                    VStack(spacing: 24) {
                        // Header balance section
                        VStack(spacing: 8) {
                            HStack(spacing: 6) {
                                Image(systemName: "bolt.fill")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.yellow)
                                Text("TRIMM CREDITS")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.muted)
                                    .tracking(1.4)
                            }
                            
                            HStack(alignment: .firstTextBaseline, spacing: 4) {
                                Text("\(billing.balance)")
                                    .font(.system(size: 48, weight: .bold, design: .rounded))
                                    .foregroundColor(.cream)
                                Text("credits")
                                    .font(.system(size: 18, weight: .medium))
                                    .foregroundColor(.muted)
                            }
                            
                            Text("Powering speech transcription and AI conversational video edits. Your video files stay securely on device.")
                                .font(.system(size: 13))
                                .foregroundColor(.muted)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 24)
                        }
                        .padding(.top, 16)
                        
                        // Package Cards
                        VStack(spacing: 12) {
                            ForEach(billing.packages) { pkg in
                                packageCard(pkg)
                            }
                        }
                        .padding(.horizontal, 16)
                        
                        // Action Button
                        if let selectedPkg = billing.packages.first(where: { $0.id == selectedPackageId }) {
                            Button(action: {
                                Task {
                                    do {
                                        try await billing.purchase(package: selectedPkg)
                                        purchasedCreditsCount = selectedPkg.credits
                                        showingPurchaseSuccess = true
                                    } catch {
                                        purchaseError = error.localizedDescription
                                    }
                                }
                            }) {
                                HStack(spacing: 8) {
                                    if billing.isPurchasing {
                                        ProgressView()
                                            .tint(.darkText)
                                    } else {
                                        Image(systemName: "applelogo")
                                            .font(.system(size: 16))
                                        Text("Top Up \(selectedPkg.credits) Credits • \(selectedPkg.priceString)")
                                            .font(.system(size: 16, weight: .semibold))
                                    }
                                }
                                .frame(maxWidth: .infinity)
                                .frame(height: 52)
                                .background(Color.cream)
                                .foregroundColor(.darkText)
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                            }
                            .disabled(billing.isPurchasing)
                            .padding(.horizontal, 16)
                        }
                        
                        // Usage Breakdown Box
                        VStack(alignment: .leading, spacing: 12) {
                            Text("HOW CREDITS ARE USED")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(.muted)
                                .tracking(1.2)
                            
                            VStack(spacing: 10) {
                                creditCostRow(
                                    icon: "wand.and.stars",
                                    title: "Timeline Edit & AI Chat",
                                    cost: "\(billing.costs.chatCost) credits"
                                )
                                Divider().background(Color.border.opacity(0.4))
                                creditCostRow(
                                    icon: "waveform",
                                    title: "Audio Transcription",
                                    cost: "\(billing.costs.transcriptionCost) credits"
                                )
                                Divider().background(Color.border.opacity(0.4))
                                creditCostRow(
                                    icon: "play.circle",
                                    title: "Real-time On-Device Preview",
                                    cost: "Free (0 credits)"
                                )
                                Divider().background(Color.border.opacity(0.4))
                                creditCostRow(
                                    icon: "square.and.arrow.up",
                                    title: "High-Res Camera Roll Export",
                                    cost: "Free (0 credits)"
                                )
                            }
                        }
                        .padding(16)
                        .background(Color.workspace)
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke(Color.border.opacity(0.6), lineWidth: 1)
                        )
                        .cornerRadius(14)
                        .padding(.horizontal, 16)
                        
                        // Footer / App Store Compliance
                        VStack(spacing: 10) {
                            Button(action: {
                                Task {
                                    await billing.restorePurchases()
                                }
                            }) {
                                Text("Restore Purchases")
                                    .font(.system(size: 12, weight: .medium))
                                    .foregroundColor(.muted)
                            }
                            
                            Text("Credits never expire and remain attached to your Trimm account. Consumable in-app purchases are charged to your Apple ID.")
                                .font(.system(size: 11))
                                .foregroundColor(.muted.opacity(0.8))
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 32)
                        }
                        .padding(.bottom, 24)
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { dismiss() }) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 20))
                            .foregroundColor(.muted)
                    }
                }
            }
            .alert("Credits Added!", isPresented: $showingPurchaseSuccess) {
                Button("Done", role: .cancel) {
                    dismiss()
                }
            } message: {
                Text("\(purchasedCreditsCount) credits have been successfully added to your account.")
            }
            .alert("Purchase Notice", isPresented: .init(
                get: { purchaseError != nil },
                set: { if !$0 { purchaseError = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(purchaseError ?? "")
            }
            .task {
                await billing.fetchCredits()
            }
        }
    }
    
    @ViewBuilder
    private func packageCard(_ pkg: CreditPackage) -> some View {
        let isSelected = selectedPackageId == pkg.id
        
        Button(action: {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                selectedPackageId = pkg.id
            }
        }) {
            HStack(spacing: 16) {
                // Radio indicator
                ZStack {
                    Circle()
                        .stroke(isSelected ? Color.cream : Color.border, lineWidth: 2)
                        .frame(width: 22, height: 22)
                    if isSelected {
                        Circle()
                            .fill(Color.cream)
                            .frame(width: 12, height: 12)
                    }
                }
                
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text("\(pkg.credits) Credits")
                            .font(.system(size: 17, weight: .bold))
                            .foregroundColor(.cream)
                        
                        if let badge = pkg.badge {
                            Text(badge)
                                .font(.system(size: 9, weight: .heavy))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(badge == "BEST VALUE" ? Color.yellow.opacity(0.2) : Color.cream.opacity(0.15))
                                .foregroundColor(badge == "BEST VALUE" ? .yellow : .cream)
                                .clipShape(Capsule())
                        }
                    }
                    
                    Text(pkg.description)
                        .font(.system(size: 12))
                        .foregroundColor(.muted)
                        .lineLimit(1)
                }
                
                Spacer()
                
                Text(pkg.priceString)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(isSelected ? .cream : .muted)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(isSelected ? Color.raised : Color.workspace)
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(isSelected ? Color.cream.opacity(0.8) : Color.border.opacity(0.5), lineWidth: isSelected ? 1.5 : 1)
            )
            .cornerRadius(14)
        }
        .buttonStyle(PlainButtonStyle())
    }
    
    @ViewBuilder
    private func creditCostRow(icon: String, title: String, cost: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 13))
                .foregroundColor(.muted)
                .frame(width: 20)
            Text(title)
                .font(.system(size: 13))
                .foregroundColor(.creamRaised)
            Spacer()
            Text(cost)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(.muted)
        }
    }
}
