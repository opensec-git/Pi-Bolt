import AVFoundation
import PiBoltProtocol
import SwiftUI

/// Scans a pairing QR code with the camera (AVFoundation). The first `pibolt://pair#` code found is handed back; other
/// QR codes are ignored. Without a camera or permission it says so and offers the pasted link instead.
struct ScanView: View {
    var found: (String) -> Void
    var close: () -> Void
    @State private var permission = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var pasting = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if permission == .authorized, AVCaptureDevice.default(for: .video) != nil {
                CameraPreview(found: found).ignoresSafeArea()
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.85), lineWidth: 3)
                    .frame(width: 250, height: 250)
            } else {
                VStack(spacing: 14) {
                    Image(systemName: "camera").font(.system(size: 32)).foregroundStyle(Pi.ink3)
                    Text(permission == .denied || permission == .restricted
                        ? "Camera access is off. Allow it in Settings, or paste the pairing link."
                        : "No camera here. Paste the pairing link instead.")
                        .font(.system(size: 16)).foregroundStyle(Pi.ink2).multilineTextAlignment(.center)
                }
                .padding(32)
            }
            VStack {
                HStack {
                    GlassButton(systemName: "xmark", label: "Close", action: close)
                    Spacer()
                }
                .padding(.horizontal, 16)
                Spacer()
                VStack(spacing: 12) {
                    Text("Scan the code from /remote-control or pi-bolt service pair")
                        .font(.system(size: 15)).foregroundStyle(.white.opacity(0.85)).multilineTextAlignment(.center)
                    Button { pasting = true } label: { Label("Paste pairing link", systemImage: "doc.on.clipboard") }
                        .buttonStyle(SecondaryButtonStyle())
                        .frame(maxWidth: 280)
                }
                .padding(.bottom, 32)
                .padding(.horizontal, Pi.gutter)
            }
        }
        .task {
            if permission == .notDetermined {
                _ = await AVCaptureDevice.requestAccess(for: .video)
                permission = AVCaptureDevice.authorizationStatus(for: .video)
            }
        }
        .sheet(isPresented: $pasting) {
            PasteSheet { text in
                pasting = false
                found(text)
            }
            .presentationDetents([.medium])
            .presentationBackground(Pi.surface1)
        }
    }
}

private struct CameraPreview: UIViewRepresentable {
    var found: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(found: found) }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.videoGravity = .resizeAspectFill
        context.coordinator.attach(to: view)
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    static func dismantleUIView(_ uiView: PreviewView, coordinator: Coordinator) {
        coordinator.stop()
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        private let session = AVCaptureSession()
        private let queue = DispatchQueue(label: "pibolt.camera")
        private let found: (String) -> Void
        private var done = false

        init(found: @escaping (String) -> Void) {
            self.found = found
        }

        func attach(to view: PreviewView) {
            view.previewLayer.session = session
            queue.async { [session] in
                guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return }
                session.beginConfiguration()
                session.addInput(input)
                let output = AVCaptureMetadataOutput()
                if session.canAddOutput(output) {
                    session.addOutput(output)
                    output.setMetadataObjectsDelegate(self, queue: .main)
                    output.metadataObjectTypes = [.qr]
                }
                session.commitConfiguration()
                session.startRunning()
            }
        }

        func stop() {
            queue.async { [session] in session.stopRunning() }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !done else { return }
            for object in metadataObjects {
                guard let code = (object as? AVMetadataMachineReadableCodeObject)?.stringValue, PairingPayload.looksLikePairing(code) else { continue }
                done = true
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                stop()
                found(code)
                return
            }
        }
    }
}
