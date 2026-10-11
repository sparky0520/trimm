import SwiftUI
import Supabase
import AuthenticationServices
import CryptoKit

struct SignInView: View {
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var currentNonce: String?
    
    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            
            TrimmLogo() // Assuming a Logo view exists based on UI folder contents
                .frame(width: 120, height: 120)
                .padding(.bottom, 32)
            
            Text("Welcome to Trimm")
                .font(.largeTitle)
                .bold()
            
            Text("Sign in to continue")
                .foregroundColor(.secondary)
            
            Spacer()
            
            if let error = errorMessage {
                Text(error)
                    .foregroundColor(.red)
                    .font(.caption)
                    .multilineTextAlignment(.center)
            }
            
            VStack(spacing: 16) {
                // Apple Sign In Button
                SignInWithAppleButton(
                    onRequest: { request in
                        let nonce = randomNonceString()
                        currentNonce = nonce
                        request.requestedScopes = [.fullName, .email]
                        request.nonce = sha256(nonce)
                    },
                    onCompletion: { result in
                        handleAppleSignIn(result: result)
                    }
                )
                .signInWithAppleButtonStyle(.black)
                .frame(height: 50)
                .cornerRadius(8)
                
                // Google Sign In Button (using Supabase Web OAuth)
                Button(action: {
                    Task {
                        await signInWithGoogle()
                    }
                }) {
                    HStack {
                        Image(systemName: "g.circle.fill")
                            .font(.title2)
                        Text("Sign in with Google")
                            .font(.headline)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Color.white)
                    .foregroundColor(.black)
                    .cornerRadius(8)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.gray.opacity(0.3), lineWidth: 1)
                    )
                }
            }
            .padding(.horizontal, 32)
            .padding(.bottom, 64)
            .disabled(isLoading)
            
            if isLoading {
                ProgressView()
            }
        }
    }
    
    // MARK: - Authentication Handlers
    
    private func handleAppleSignIn(result: Result<ASAuthorization, Error>) {
        switch result {
        case .success(let authorization):
            if let appleIDCredential = authorization.credential as? ASAuthorizationAppleIDCredential {
                guard let nonce = currentNonce else {
                    errorMessage = "Invalid state: A login nonce was not found."
                    return
                }
                guard let appleIDToken = appleIDCredential.identityToken,
                      let idTokenString = String(data: appleIDToken, encoding: .utf8) else {
                    errorMessage = "Unable to fetch identity token."
                    return
                }
                
                isLoading = true
                Task {
                    do {
                        try await supabase.auth.signInWithIdToken(
                            credentials: .init(
                                provider: .apple,
                                idToken: idTokenString,
                                nonce: nonce
                            )
                        )
                        
                        if let fullName = appleIDCredential.fullName {
                            var nameParts: [String] = []
                            if let given = fullName.givenName, !given.isEmpty { nameParts.append(given) }
                            if let family = fullName.familyName, !family.isEmpty { nameParts.append(family) }
                            let fullNameString = nameParts.joined(separator: " ")
                            
                            if !fullNameString.isEmpty {
                                try? await supabase.auth.update(
                                    user: UserAttributes(
                                        data: [
                                            "full_name": .string(fullNameString),
                                            "given_name": .string(fullName.givenName ?? ""),
                                            "family_name": .string(fullName.familyName ?? "")
                                        ]
                                    )
                                )
                            }
                        }
                    } catch {
                        errorMessage = error.localizedDescription
                        isLoading = false
                    }
                    currentNonce = nil
                }
            }
        case .failure(let error):
            if (error as? ASAuthorizationError)?.code != .canceled {
                errorMessage = error.localizedDescription
            }
        }
    }
    
    @MainActor
    private func signInWithGoogle() async {
        isLoading = true
        errorMessage = nil
        do {
            let url = try supabase.auth.getOAuthSignInURL(
                provider: .google,
                redirectTo: URL(string: "ai.trimm.mobile://login-callback")
            )
            
            // Note: Since macOS 11/iOS 14+, prefer using ASWebAuthenticationSession natively.
            // Using `UIApplication.shared.open(url)` as a fallback or implementing ASWebAuthenticationSession here.
            // For simplicity, opening the URL directly, but in a real app use ASWebAuthenticationSession.
            if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let window = windowScene.windows.first,
               let rootViewController = window.rootViewController {
                
                let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "ai.trimm.mobile") { callbackURL, error in
                    if let error = error {
                        self.errorMessage = error.localizedDescription
                        self.isLoading = false
                        return
                    }
                    guard let callbackURL = callbackURL else {
                        self.isLoading = false
                        return
                    }
                    
                    Task {
                        do {
                            try await supabase.auth.session(from: callbackURL)
                        } catch {
                            self.errorMessage = error.localizedDescription
                        }
                        self.isLoading = false
                    }
                }
                
                session.presentationContextProvider = rootViewController as? any ASWebAuthenticationPresentationContextProviding
                session.start()
            } else {
                isLoading = false
            }
        } catch {
            errorMessage = error.localizedDescription
            isLoading = false
        }
    }
    
    // MARK: - Helpers
    
    private func randomNonceString(length: Int = 32) -> String {
        precondition(length > 0)
        let charset: [Character] =
            Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var result = ""
        var remainingLength = length
        
        while remainingLength > 0 {
            let randoms: [UInt8] = (0 ..< 16).map { _ in
                var random: UInt8 = 0
                let errorCode = SecRandomCopyBytes(kSecRandomDefault, 1, &random)
                if errorCode != errSecSuccess {
                    fatalError("Unable to generate nonce. SecRandomCopyBytes failed with OSStatus \(errorCode)")
                }
                return random
            }
            
            randoms.forEach { random in
                if remainingLength == 0 {
                    return
                }
                if random < charset.count {
                    result.append(charset[Int(random)])
                    remainingLength -= 1
                }
            }
        }
        return result
    }
    
    private func sha256(_ input: String) -> String {
        let inputData = Data(input.utf8)
        let hashedData = SHA256.hash(data: inputData)
        let hashString = hashedData.compactMap {
            String(format: "%02x", $0)
        }.joined()
        return hashString
    }
}
