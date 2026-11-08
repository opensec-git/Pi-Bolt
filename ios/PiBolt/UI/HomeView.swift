import PiBoltProtocol
import SwiftUI

/// Home: an identity circle and the pair button on top, a quiet title, status tiles from real state, then the paired
/// computers as a plain list (android/DESIGN.md and ~/pbw/research/UI-REFERENCES.md, "Inbox").
struct HomeView: View {
    @Environment(AppModel.self) private var model
    var openSession: () -> Void
    @State private var scanning = false
    @State private var pasting = false
    @State private var showDevice = false

    var body: some View {
        HomeList(store: model.hosts, recents: model.recents, openSession: openSession, scanning: $scanning, pasting: $pasting, showDevice: $showDevice)
            .background(Pi.canvas.ignoresSafeArea())
            .fullScreenCover(isPresented: $scanning) {
                ScanView { text in
                    scanning = false
                    model.receive(link: text)
                } close: {
                    scanning = false
                }
            }
            .sheet(isPresented: $pasting) {
                PasteSheet { text in
                    pasting = false
                    model.receive(link: text)
                }
                .presentationDetents([.medium])
                .presentationBackground(Pi.surface1)
            }
            .sheet(isPresented: $showDevice) {
                DeviceSheet()
                    .presentationDetents([.medium])
                    .presentationBackground(Pi.surface1)
            }
    }
}

private struct HomeList: View {
    @Environment(AppModel.self) private var model
    @ObservedObject var store: HostStore
    @ObservedObject var recents: RecentStore
    var openSession: () -> Void
    @Binding var scanning: Bool
    @Binding var pasting: Bool
    @Binding var showDevice: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                Text("Pi-Bolt")
                    .font(.system(size: 32, weight: .semibold))
                    .foregroundStyle(Pi.ink1)
                    .padding(.top, 18)
                Text("Remote control for Pi-Bolt on your computer")
                    .font(.system(size: 15))
                    .foregroundStyle(Pi.ink3)
                    .padding(.top, 4)
                if store.hosts.isEmpty {
                    EmptyHome(scan: { scanning = true }, paste: { pasting = true })
                        .padding(.top, 36)
                } else {
                    tiles.padding(.top, 24)
                    if !recents.sessions.isEmpty { recentSection }
                    Text("Computers")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Pi.ink3)
                        .padding(.top, 32)
                        .padding(.bottom, 6)
                    ForEach(store.hosts) { host in
                        HostRow(host: host, live: model.live?.hostId == host.id ? model.live : nil) {
                            model.open(host)
                            openSession()
                        }
                        .contextMenu {
                            Button(role: .destructive) { model.forget(host) } label: { Label("Forget", systemImage: "trash") }
                        }
                        if host.id != store.hosts.last?.id {
                            Rectangle().fill(Pi.line).frame(height: 1).padding(.leading, 48)
                        }
                    }
                }
            }
            .padding(.horizontal, Pi.gutter)
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
    }

    private var header: some View {
        HStack {
            Button { showDevice = true } label: {
                ZStack {
                    Circle().fill(Pi.surface2)
                    Circle().strokeBorder(Pi.lineStrong, lineWidth: 1)
                    BrandMark(size: 24)
                }
                .frame(width: 44, height: 44)
            }
            .buttonStyle(PressScale())
            .frame(minWidth: 48, minHeight: 48)
            .accessibilityLabel("This phone")
            Spacer()
            Menu {
                Button { scanning = true } label: { Label("Scan QR code", systemImage: "qrcode.viewfinder") }
                Button { pasting = true } label: { Label("Paste pairing link", systemImage: "doc.on.clipboard") }
            } label: {
                GlassCircle(systemName: "plus")
            }
            .frame(minWidth: 48, minHeight: 48)
            .accessibilityLabel("Pair a computer")
        }
        .padding(.top, 8)
    }

    /// The sessions this phone showed last, on any paired computer. A tap opens that computer and, with its
    /// background service, that very session.
    @ViewBuilder private var recentSection: some View {
        Text("Recents")
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(Pi.ink3)
            .padding(.top, 32)
            .padding(.bottom, 6)
        let shown = Array(recents.sessions.filter { store.get($0.hostId) != nil }.prefix(5))
        ForEach(shown) { recent in
            RecentRow(recent: recent, live: liveFor(recent)) {
                model.openRecent(recent)
            }
            .contextMenu {
                Button(role: .destructive) { recents.remove(recent) } label: { Label("Remove from Recents", systemImage: "minus.circle") }
            }
            if recent.id != shown.last?.id {
                Rectangle().fill(Pi.line).frame(height: 1).padding(.leading, 48)
            }
        }
    }

    /// The live session when it shows `recent`.
    private func liveFor(_ recent: RecentSession) -> SessionController? {
        guard let live = model.live, live.hostId == recent.hostId, live.state.sessionId == recent.sessionId else { return nil }
        return live
    }

    private var tiles: some View {
        let live = model.live
        let running = live?.state.isRunning == true ? 1 : 0
        let waiting = live?.state.dialogs.count ?? 0
        let online = live?.link.isConnected == true ? 1 : 0
        return LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
            StatusTile(glyph: "laptopcomputer", tint: Pi.ink2, label: "Computers", count: store.hosts.count)
            StatusTile(glyph: "dot.radiowaves.left.and.right", tint: Pi.success, label: "Connected", count: online)
            StatusTile(glyph: "bolt.fill", tint: Pi.ink1, label: "Working", count: running, spinning: running > 0)
            StatusTile(glyph: "hand.raised.fill", tint: Pi.warning, label: "Waiting for you", count: waiting)
        }
    }
}

private struct StatusTile: View {
    var glyph: String
    var tint: Color
    var label: String
    var count: Int
    var spinning = false

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 14) {
                if spinning {
                    QuadSpinner(size: 18, color: tint)
                } else {
                    Image(systemName: glyph).font(.system(size: 16, weight: .semibold)).foregroundStyle(tint)
                }
                HStack(alignment: .firstTextBaseline) {
                    Text(label).font(.system(size: 15, weight: .medium)).foregroundStyle(Pi.ink2)
                    Spacer()
                    Text("\(count)").font(.system(size: 17, weight: .semibold)).foregroundStyle(Pi.ink1)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct HostRow: View {
    var host: PairedHost
    var live: SessionController?
    var open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: 16) {
                if live?.state.isRunning == true {
                    QuadSpinner(size: 18)
                        .frame(width: 32)
                } else {
                    Image(systemName: "laptopcomputer").font(.system(size: 18)).foregroundStyle(Pi.ink2).frame(width: 32)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(host.name).font(.system(size: 17, weight: .medium)).foregroundStyle(Pi.ink1).lineLimit(1)
                    Text(subtitle).font(.system(size: 14)).foregroundStyle(Pi.ink3).lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Pi.ink4)
            }
            .padding(.vertical, 15)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
    }

    private var subtitle: String {
        var parts: [String] = []
        if let live {
            switch live.link {
            case .connected: parts.append(live.state.isRunning ? "Working" : "Connected")
            case .connecting: parts.append("Connecting")
            case .retrying: parts.append("Reconnecting")
            case .failed: parts.append("Offline")
            }
        }
        parts.append(contentsOf: host.connections)
        if host.service { parts.append("Service") }
        if let last = host.lastConnectedAt, live == nil { parts.append(relativeShort(last)) }
        return parts.joined(separator: " · ")
    }
}

private struct RecentRow: View {
    var recent: RecentSession
    var live: SessionController?
    var open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: 16) {
                if live?.state.isRunning == true {
                    QuadSpinner(size: 16).frame(width: 32)
                } else if live?.state.dialogs.isEmpty == false {
                    Image(systemName: "hand.raised.fill").font(.system(size: 15)).foregroundStyle(Pi.warning).frame(width: 32)
                } else {
                    Image(systemName: "text.bubble").font(.system(size: 17)).foregroundStyle(Pi.ink3).frame(width: 32)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(recent.title).font(.system(size: 17, weight: .medium)).foregroundStyle(Pi.ink1).lineLimit(1)
                    Text(subtitle).font(.system(size: 14)).foregroundStyle(Pi.ink3).lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Pi.ink4)
            }
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .accessibilityLabel("\(recent.title), on \(recent.hostName)")
    }

    private var subtitle: String {
        var parts = [recent.hostName]
        if !recent.folder.isEmpty { parts.append(recent.folder) }
        if live?.state.isRunning == true { parts.append("Working") } else { parts.append(relativeShort(recent.updatedAt)) }
        return parts.joined(separator: " · ")
    }
}

private struct EmptyHome: View {
    var scan: () -> Void
    var paste: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            BrandMark(size: 56)
            VStack(alignment: .leading, spacing: 8) {
                Text("Pair with your computer").font(.system(size: 22, weight: .semibold)).foregroundStyle(Pi.ink1)
                Text("Pi runs on your computer; this app drives it. On the computer, run one of:")
                    .font(.system(size: 16)).foregroundStyle(Pi.ink2)
            }
            VStack(alignment: .leading, spacing: 10) {
                CommandHint(text: "/remote-control", note: "in a Pi-Bolt terminal")
                CommandHint(text: "pi-bolt service pair", note: "with the background service")
            }
            Text("Then scan the QR code it shows. The phone and the computer should be on the same network, unless the computer has a Cloudflare tunnel.")
                .font(.system(size: 15)).foregroundStyle(Pi.ink3)
            VStack(spacing: 12) {
                Button(action: scan) { Label("Scan QR code", systemImage: "qrcode.viewfinder") }
                    .buttonStyle(PrimaryButtonStyle())
                Button(action: paste) { Label("Paste pairing link", systemImage: "doc.on.clipboard") }
                    .buttonStyle(SecondaryButtonStyle())
            }
            .padding(.top, 4)
        }
    }
}

private struct CommandHint: View {
    var text: String
    var note: String

    var body: some View {
        HStack(spacing: 10) {
            Text(text).font(Pi.mono).foregroundStyle(Pi.ink1)
                .lineLimit(1)
                .fixedSize()
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: 8).fill(Pi.surface2))
            Text(note).font(.system(size: 14)).foregroundStyle(Pi.ink3)
        }
    }
}

/// This phone's key as computers list it, and the reset.
private struct DeviceSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var confirmReset = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("This phone").font(.system(size: 22, weight: .semibold)).foregroundStyle(Pi.ink1)
            Text("Computers list it as \(model.deviceName), key \(model.identity.keyId). Revoke it there with /remote-control devices.")
                .font(.system(size: 15)).foregroundStyle(Pi.ink2)
            Text("Pi-Bolt for iOS \(model.appVersion)").font(.system(size: 14)).foregroundStyle(Pi.ink3)
            Spacer()
            Button("New device key") { confirmReset = true }
                .buttonStyle(SecondaryButtonStyle())
        }
        .padding(Pi.gutter)
        .confirmationDialog("A new key unpairs every computer: each one needs a new QR code.", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("Make a new key", role: .destructive) {
                model.resetDeviceKey()
                dismiss()
            }
        }
    }
}

/// "Paste pairing link": for a phone without a camera, or a link copied with `/remote-control copy`.
struct PasteSheet: View {
    var submit: (String) -> Void
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Paste pairing link").font(.system(size: 22, weight: .semibold)).foregroundStyle(Pi.ink1)
            Text("Run /remote-control copy (or pi-bolt service pair --link) on the computer, then paste the link here. It pairs one phone within 10 minutes.")
                .font(.system(size: 15)).foregroundStyle(Pi.ink2)
            TextField("pibolt://pair#…", text: $text, axis: .vertical)
                .font(Pi.mono)
                .lineLimit(2 ... 5)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 14).fill(Pi.surface2))
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Pi.lineStrong, lineWidth: 1))
                .foregroundStyle(Pi.ink1)
            HStack(spacing: 12) {
                PasteButton(payloadType: String.self) { strings in
                    if let first = strings.first { Task { @MainActor in text = first } }
                }
                .labelStyle(.titleAndIcon)
                .tint(Pi.surface3)
                Spacer()
                Button("Use link") { submit(text) }
                    .buttonStyle(PrimaryButtonStyle())
                    .frame(maxWidth: 160)
                    .disabled(!PairingPayload.looksLikePairing(text))
                    .opacity(PairingPayload.looksLikePairing(text) ? 1 : 0.4)
            }
            Spacer(minLength: 0)
        }
        .padding(Pi.gutter)
    }
}
