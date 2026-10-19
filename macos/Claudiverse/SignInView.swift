import SwiftUI

/// The server, and the operator's email and password. Signing in mints a token for this Mac, kept in a file only your account can read.
struct SignInView: View {
    @Environment(AppModel.self) private var model
    @State private var server = ""
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "sparkles").font(.system(size: 44)).foregroundStyle(Theme.accent)
            Text("Sign in to Claudiverse").font(.title2.bold())
            Form {
                TextField("Server", text: $server, prompt: Text("http://server:4010"))
                TextField("Email", text: $email).textContentType(.username)
                SecureField("Password", text: $password).textContentType(.password)
            }
            .formStyle(.grouped)
            .themedList()
            .scrollDisabled(true)
            .frame(width: 420)
            .fixedSize(horizontal: false, vertical: true)
            .disabled(busy)
            .onSubmit(signIn)

            if let error {
                Text(error).foregroundStyle(Theme.bad).font(.callout).multilineTextAlignment(.center).frame(width: 400)
            }

            Button(action: signIn) {
                if busy { ProgressView().controlSize(.small) } else { Text("Sign in").frame(minWidth: 120) }
            }
            .buttonStyle(.borderedProminent)
            .keyboardShortcut(.defaultAction)
            .disabled(busy || server.isEmpty || email.isEmpty || password.isEmpty)

            Text("This Mac appears in the server's token list as “\(AppModel.clientName())”.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear { server = model.serverURL }
    }

    private func signIn() {
        guard !busy, !server.isEmpty, !email.isEmpty, !password.isEmpty else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                try await model.signIn(server: server, email: email, password: password)
                password = ""
            } catch {
                self.error = Self.reason(error)
            }
        }
    }

    /// What went wrong, in the operator's terms. A rate limit says how long to wait, and nothing retries by itself.
    static func reason(_ error: Error) -> String {
        switch error.httpStatus {
        case 401: return "Wrong email or password."
        case 429:
            if let wait = error.apiException?.retryAfterSeconds { return "Too many attempts. Try again in \(wait) s." }
            return "Too many attempts. Wait a minute and try again."
        case 400: return "The server refused the request: \(error.message)"
        default: return "Could not sign in: \(error.message)"
        }
    }
}
