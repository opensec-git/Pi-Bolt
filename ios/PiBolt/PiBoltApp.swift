import PiBoltMarkdown
import PiBoltProtocol
import SwiftUI

@main
struct PiBoltApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                .tint(Pi.accent)
                // Every link a view opens goes through the allowlist too, whatever made it a link.
                .environment(\.openURL, OpenURLAction { SafeLinks.isAllowed($0) ? .systemAction : .discarded })
                // A pibolt://pair#… link from the camera app, a browser or another app.
                .onOpenURL { model.receive(link: $0.absoluteString) }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.appBecameActive() }
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var showSession = false

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            HomeView(openSession: { showSession = true })
                .navigationDestination(isPresented: $showSession) {
                    if let live = model.live {
                        SessionView(controller: live, close: { showSession = false })
                            .toolbar(.hidden, for: .navigationBar)
                    }
                }
                .toolbar(.hidden, for: .navigationBar)
        }
        .background(Pi.canvas.ignoresSafeArea())
        .sheet(item: $model.pendingLink) { payload in
            PairConfirmSheet(payload: payload, known: model.hosts.get(payload.hostKeyId) != nil) {
                model.pendingLink = nil
                model.pair(payload)
                showSession = true
            } cancel: {
                model.pendingLink = nil
            }
            .presentationDetents([.medium])
            .presentationBackground(Pi.surface1)
        }
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                ToastView(toast: toast)
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: toast.id) {
                        try? await Task.sleep(nanoseconds: 3_500_000_000)
                        if model.toast?.id == toast.id { model.toast = nil }
                    }
            }
        }
        .animation(.easeOut(duration: 0.18), value: model.toast)
        #if DEBUG
            .task { await autopilot() }
        #endif
    }

    #if DEBUG
        /// CI screenshots (`.github/workflows/ios.yml`): `PIBOLT_AUTOPAIR` pairs with that link and opens the session,
        /// `PIBOLT_AUTOPROMPT` then sends a prompt. Debug builds only.
        private func autopilot() async {
            let env = ProcessInfo.processInfo.environment
            guard let link = env["PIBOLT_AUTOPAIR"], let payload = try? PairingPayload.parse(link) else { return }
            let controller = model.pair(payload)
            showSession = true
            guard let prompt = env["PIBOLT_AUTOPROMPT"] else { return }
            for _ in 0 ..< 200 where !(controller.link.isConnected && controller.state.hello) {
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            controller.send(prompt)
        }
    #endif
}

/// Before pairing: which computer, over which addresses.
struct PairConfirmSheet: View {
    var payload: PairingPayload
    var known: Bool
    var confirm: () -> Void
    var cancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 14) {
                GlassCircle(systemName: "laptopcomputer", size: 48, iconSize: 20)
                VStack(alignment: .leading, spacing: 4) {
                    Text(known ? "Reconnect to \(payload.hostName)" : "Pair with \(payload.hostName)")
                        .font(.system(size: 22, weight: .semibold)).foregroundStyle(Pi.ink1)
                    Text(known ? "This phone knows this computer. The code's addresses are used from now on." : "This phone will be able to run Pi on this computer, with your permissions there.")
                        .font(.system(size: 15)).foregroundStyle(Pi.ink2)
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                ForEach(payload.endpoints, id: \.self) { endpoint in
                    Text(endpoint).font(Pi.monoSmall).foregroundStyle(Pi.ink3).lineLimit(1).truncationMode(.middle)
                }
            }
            Text("Key \(String(payload.hostKeyId.prefix(8)))").font(Pi.monoSmall).foregroundStyle(Pi.ink3)
            Spacer(minLength: 0)
            HStack(spacing: 12) {
                Button("Cancel", action: cancel)
                    .buttonStyle(SecondaryButtonStyle())
                Button(known ? "Connect" : "Pair", action: confirm)
                    .buttonStyle(PrimaryButtonStyle())
            }
        }
        .padding(Pi.gutter)
        .padding(.top, 8)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(Pi.onAccent)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(Capsule().fill(Pi.accent))
            .opacity(configuration.isPressed ? 0.85 : 1)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .medium))
            .foregroundStyle(Pi.ink1)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(Capsule().fill(Pi.surface2))
            .overlay(Capsule().strokeBorder(Pi.lineStrong, lineWidth: 1))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
    }
}
