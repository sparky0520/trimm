import SwiftUI
import SwiftData
import Supabase
import PhotosUI
import CoreTransferable
import AuthenticationServices
import GoogleSignIn
import CryptoKit

struct MovieFile: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
            try FileManager.default.copyItem(at: received.file, to: copy)
            return Self.init(url: copy)
        }
    }
}

struct SidebarView: View {
    @Bindable var viewModel: EditorViewModel
    @Binding var isDrawerOpen: Bool
    @State private var chatToDelete: String? = nil
    @State private var currentUser: User? = nil
    @State private var showSignOutConfirmation = false
    @State private var billing = BillingManager.shared
    
    var body: some View {
        VStack(spacing: 0) {
            // Custom Header matching Android AppWithDrawer
            HStack {
                Text("Trimm")
                    .font(.system(size: 24, weight: .semibold))
                    .tracking(-1)
                    .foregroundColor(.primary)
                
                Spacer()
                
                Button(action: {
                    viewModel.state.project = nil
                    viewModel.state.sources = []
                    viewModel.player?.pause()
                    viewModel.player = nil
                    withAnimation { isDrawerOpen = false }
                }) {
                    Text("+")
                        .font(.system(size: 24))
                        .foregroundColor(viewModel.state.isLoadingPastChats ? .gray : .primary)
                }
                .disabled(viewModel.state.isLoadingPastChats)
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 8)
            
            List {
                Section(header: HStack {
                    Text("Past Chats")
                        .textCase(nil)
                        .font(.subheadline)
                        .foregroundColor(.gray)
                    if viewModel.state.isLoadingPastChats {
                        Spacer()
                        ProgressView().scaleEffect(0.5)
                    }
                }) {
                    if viewModel.state.pastChats.isEmpty && !viewModel.state.isLoadingPastChats {
                        Text("No past chats.")
                            .foregroundColor(.gray)
                            .italic()
                            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                            .listRowBackground(Color.clear)
                    } else {
                        ForEach(viewModel.state.pastChats) { chat in
                            Button(action: {
                                Task { await viewModel.loadProject(id: chat.id) }
                                withAnimation { isDrawerOpen = false }
                            }) {
                                Text(chat.title)
                            }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    chatToDelete = chat.id
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                            .listRowBackground(Color.clear)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .padding(.horizontal, 16)
            
            if currentUser != nil {
                Button(action: {
                    billing.isStorePresented = true
                }) {
                    HStack(spacing: 8) {
                        Image(systemName: "bolt.fill")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.yellow)
                        Text("\(billing.balance) Credits")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.cream)
                        Spacer()
                        Text("Top up")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.darkText)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.cream)
                            .clipShape(Capsule())
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(Color.raised)
                    .cornerRadius(10)
                }
                .buttonStyle(PlainButtonStyle())
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }
            
            Divider()
            
            if let user = currentUser {
                Button(action: { showSignOutConfirmation = true }) {
                    HStack(spacing: 12) {
                        let metadata = user.userMetadata
                        let avatarUrlString = metadata["avatar_url"]?.value as? String ?? ""
                        let fullName = metadata["full_name"]?.value as? String ?? user.email ?? "User"
                        
                        if let url = URL(string: avatarUrlString), !avatarUrlString.isEmpty {
                            AsyncImage(url: url) { image in
                                image.resizable().scaledToFill()
                            } placeholder: {
                                Image(systemName: "person.crop.circle.fill").resizable().foregroundColor(.gray)
                            }
                            .frame(width: 32, height: 32)
                            .clipShape(Circle())
                        } else {
                            Image(systemName: "person.crop.circle.fill")
                                .resizable()
                                .frame(width: 32, height: 32)
                                .foregroundColor(.gray)
                        }
                        
                        Text(fullName)
                            .font(.callout)
                            .lineLimit(1)
                            .foregroundColor(.primary)
                        
                        Spacer()
                    }
                    .padding()
                    .contentShape(Rectangle())
                }
                .buttonStyle(PlainButtonStyle())
                .confirmationDialog("Account", isPresented: $showSignOutConfirmation, titleVisibility: .visible) {
                    Button("Sign out", role: .destructive) {
                        Task { try? await supabase.auth.signOut() }
                    }
                } message: {
                    Text(user.email ?? "")
                }
            }
        }
        .navigationBarHidden(true)
        .alert("Delete Chat", isPresented: .init(
            get: { chatToDelete != nil },
            set: { if !$0 { chatToDelete = nil } }
        )) {
            Button("Delete", role: .destructive) {
                if let id = chatToDelete {
                    Task { await viewModel.deleteChat(id: id) }
                }
                chatToDelete = nil
            }
            Button("Cancel", role: .cancel) {
                chatToDelete = nil
            }
        } message: {
            Text("Are you sure you want to delete this chat? This action cannot be undone.")
        }
        .task {
            for await state in supabase.auth.authStateChanges {
                currentUser = state.session?.user
            }
        }
    }
}

struct ContentView: View {
    @State private var viewModel = EditorViewModel()
    @State private var selectedItem: PhotosPickerItem?
    @State private var isAuthenticated = false
    @State private var isLoggingIn = false
    @State private var errorMessage: String?
    @State private var isDrawerOpen = false
    @State private var appleSignInNonce: String?
    @State private var billing = BillingManager.shared
    
    var body: some View {
        ZStack(alignment: .leading) {
            NavigationStack {
                ZStack {
                    Color.ink.ignoresSafeArea()
                
                if viewModel.state.sources.isEmpty && viewModel.state.project == nil {
                    VStack(spacing: 24) {
                        Spacer()
                        
                        TrimmLogo()
                            .frame(width: 108, height: 108)
                        Text("START A NEW EDIT")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.muted)
                            .tracking(1.4)
                        
                        Text("Turn a rough recording\ninto something worth sharing.")
                            .font(.system(size: 27))
                            .foregroundColor(.creamRaised)
                            .multilineTextAlignment(.center)
                            .lineSpacing(4)
                        
                        Text("Bring in your talking-head and product clips, then shape them naturally with AI.")
                            .font(.system(size: 15))
                            .foregroundColor(.muted)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 40)
                        
                        if !isAuthenticated {
                            VStack(spacing: 16) {
                                SignInWithAppleButton(.signIn) { request in
                                    request.requestedScopes = [.fullName, .email]
                                    let rawNonce = randomNonceString()
                                    appleSignInNonce = rawNonce
                                    request.nonce = sha256(rawNonce)
                                } onCompletion: { result in
                                    handleAppleSignIn(result: result)
                                }
                                .signInWithAppleButtonStyle(.white)
                                .frame(height: 50)
                                .cornerRadius(8)
                                
                                Button(action: handleGoogleSignIn) {
                                    HStack(spacing: 8) {
                                        Image("google_logo")
                                            .resizable()
                                            .scaledToFit()
                                            .frame(width: 18, height: 18)
                                        
                                        Text("Sign in with Google")
                                            .font(.system(size: 19, weight: .medium))
                                            .foregroundColor(.black)
                                    }
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                                    .background(Color.white)
                                    .cornerRadius(8)
                                }
                                .frame(height: 50)
                            }
                            .padding(.horizontal, 40)
                            
                            if let error = errorMessage {
                                Text(error)
                                    .foregroundColor(.red)
                                    .font(.caption)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal)
                            }
                            if isLoggingIn {
                                ProgressView().padding()
                            }
                        } else {
                            if viewModel.state.isProcessing {
                                ProgressView("Importing media...")
                                    .padding()
                                    .foregroundColor(.muted)
                            }
                            PhotosPicker(selection: $selectedItem, matching: .videos) {
                                Text("Select clips to edit")
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundColor(.darkText)
                                    .padding()
                                    .frame(maxWidth: .infinity)
                                    .background(Color.coral)
                                    .cornerRadius(10)
                                    .padding(.horizontal, 40)
                            }
                            .disabled(viewModel.state.isProcessing)
                            .onChange(of: selectedItem) { _, newValue in
                                if let newValue = newValue {
                                    Task {
                                        if let movie = try? await newValue.loadTransferable(type: MovieFile.self) {
                                            await viewModel.importSources(urls: [movie.url])
                                            await MainActor.run {
                                                viewModel.initializeProject()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer()
                        
                        Text("YOUR VIDEO STAYS ON DEVICE")
                            .font(.system(size: 9, weight: .regular))
                            .foregroundColor(.muted)
                            .tracking(0.8)
                            .padding(.bottom, 32)
                    }
                } else {
                    EditorScreen(viewModel: viewModel)
                }
            } // ZStack
            .navigationBarBackButtonHidden(true)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: {
                        Task { await viewModel.loadPastChats() }
                        withAnimation { isDrawerOpen = true }
                    }) {
                        Image(systemName: "line.3.horizontal")
                            .foregroundColor(.primary)
                    }
                }
                
                if isAuthenticated {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(action: {
                            billing.isStorePresented = true
                        }) {
                            HStack(spacing: 5) {
                                Image(systemName: "bolt.fill")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(.yellow)
                                Text("\(billing.balance)")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.cream)
                            }
                            .padding(.horizontal, 9)
                            .padding(.vertical, 4)
                            .background(Color.raised)
                            .clipShape(Capsule())
                            .overlay(
                                Capsule().stroke(Color.border.opacity(0.6), lineWidth: 1)
                            )
                        }
                    }
                }
            }
            } // NavigationStack
            
            if isDrawerOpen {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .onTapGesture {
                        withAnimation { isDrawerOpen = false }
                    }
                
                SidebarView(viewModel: viewModel, isDrawerOpen: $isDrawerOpen)
                    .frame(width: UIScreen.main.bounds.width * 0.75)
                    .background(Color(UIColor.systemBackground))
                    .transition(.move(edge: .leading))
                    .gesture(
                        DragGesture().onEnded { value in
                            if value.translation.width < -50 {
                                withAnimation { isDrawerOpen = false }
                            }
                        }
                    )
                    .ignoresSafeArea(.all, edges: .bottom)
                    .zIndex(1)
            }
        } // ZStack
        .preferredColorScheme(.dark)
        .sheet(isPresented: $billing.isStorePresented) {
            CreditStoreView()
        }
        .task {
            for await authEvent in supabase.auth.authStateChanges {
                let wasAuthenticated = isAuthenticated
                isAuthenticated = authEvent.session != nil
                await billing.updateUser(userId: authEvent.session?.user.id.uuidString)
                if !wasAuthenticated && isAuthenticated {
                    Task { await viewModel.loadPastChats() }
                }
            }
        }
    } // body
    
    private func handleAppleSignIn(result: Result<ASAuthorization, Error>) {
        switch result {
        case .success(let authorization):
            guard let appleIDCredential = authorization.credential as? ASAuthorizationAppleIDCredential,
                  let identityTokenData = appleIDCredential.identityToken,
                  let identityToken = String(data: identityTokenData, encoding: .utf8) else {
                errorMessage = "Unable to fetch identity token from Apple."
                return
            }
            
            guard let nonce = appleSignInNonce else {
                errorMessage = "Invalid state: A login nonce was not found."
                return
            }
            
            Task {
                isLoggingIn = true
                errorMessage = nil
                do {
                    try await supabase.auth.signInWithIdToken(
                        credentials: .init(provider: .apple, idToken: identityToken, nonce: nonce)
                    )
                    
                    // Apple only provides the user's full name on the initial sign-in.
                    // Save it to Supabase user metadata if available:
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
                }
                isLoggingIn = false
                appleSignInNonce = nil
            }
        case .failure(let error):
            if (error as? ASAuthorizationError)?.code != .canceled {
                errorMessage = error.localizedDescription
            }
        }
    }
    
    private func handleGoogleSignIn() {
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootViewController = windowScene.windows.first?.rootViewController else {
            return
        }
        
        Task {
            isLoggingIn = true
            do {
                let clientID = Secrets.googleIosClientId
                let serverClientID = Secrets.googleWebClientId
                if clientID.isEmpty || clientID == "YOUR_IOS_CLIENT_ID_HERE" {
                    throw NSError(domain: "Auth", code: 1, userInfo: [NSLocalizedDescriptionKey: "Google iOS Client ID not configured"])
                }
                let config = GIDConfiguration(clientID: clientID, serverClientID: serverClientID)
                GIDSignIn.sharedInstance.configuration = config
                
                let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: rootViewController)
                if let idToken = result.user.idToken?.tokenString {
                    try await supabase.auth.signInWithIdToken(
                        credentials: .init(provider: .google, idToken: idToken)
                    )
                }
            } catch {
                errorMessage = error.localizedDescription
            }
            isLoggingIn = false
        }
    }
}

// MARK: - Apple Sign In Nonce Helpers
private func randomNonceString(length: Int = 32) -> String {
    precondition(length > 0)
    var randomBytes = [UInt8](repeating: 0, count: length)
    let errorCode = SecRandomCopyBytes(kSecRandomDefault, randomBytes.count, &randomBytes)
    if errorCode != errSecSuccess {
        fatalError("Unable to generate nonce. SecRandomCopyBytes failed with OSStatus \(errorCode)")
    }
    
    let charset: [Character] =
        Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
    
    let nonce = randomBytes.map { byte in
        charset[Int(byte) % charset.count]
    }
    
    return String(nonce)
}

@available(iOS 13, *)
private func sha256(_ input: String) -> String {
    let inputData = Data(input.utf8)
    let hashedData = SHA256.hash(data: inputData)
    let hashString = hashedData.compactMap {
        String(format: "%02x", $0)
    }.joined()
    
    return hashString
}

#Preview {
    ContentView()
}
