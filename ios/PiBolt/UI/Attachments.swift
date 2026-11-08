import PhotosUI
import PiBoltProtocol
import SwiftUI
import UIKit

/// An image attached to the next prompt: its thumbnail at once, its encoding (`ImageEncoder`) when ready.
struct PendingImage: Identifiable, Equatable {
    let id = UUID()
    var thumbnail: UIImage?
    var attachment: ImageAttachment?
    var ready: Bool { attachment != nil }

    static func == (a: PendingImage, b: PendingImage) -> Bool { a.id == b.id && a.attachment == b.attachment && a.thumbnail === b.thumbnail }
}

/// The composer's images: added from the photo library, the camera or the clipboard, encoded off the main thread.
@MainActor
@Observable
final class Attachments {
    private(set) var images: [PendingImage] = []
    var error: String?

    var slotsLeft: Int { ImageSizing.slotsLeft(images.count) }
    var encoding: Bool { images.contains { !$0.ready } }
    var ready: [ImageAttachment] { images.compactMap(\.attachment) }

    func add(_ data: Data) {
        guard let id = reserve() else { return }
        fill(id, data)
    }

    /// A placeholder taken before an image's data is there (a photo still loading from iCloud), so the composer waits
    /// for it and a send or `clear()` drops it. Nil when no slot is left.
    private func reserve() -> UUID? {
        guard slotsLeft > 0 else {
            error = "At most \(ImageSizing.maxImages) images per message."
            return nil
        }
        let pending = PendingImage()
        images.append(pending)
        return pending.id
    }

    /// The data of placeholder `id`, unless it was removed meanwhile.
    private func fill(_ id: UUID, _ data: Data) {
        guard let index = images.firstIndex(where: { $0.id == id }) else { return }
        images[index].thumbnail = ImageEncoder.thumbnail(data).map { UIImage(cgImage: $0) }
        Task.detached(priority: .userInitiated) { [weak self] in
            let result = ImageEncoder.encode(data)
            await self?.finish(id, result)
        }
    }

    func add(_ image: UIImage) {
        guard let data = image.jpegData(compressionQuality: 0.95) ?? image.pngData() else {
            error = ImageEncodingError.unreadable.description
            return
        }
        add(data)
    }

    func load(_ items: [PhotosPickerItem]) {
        for item in items.prefix(slotsLeft) {
            guard let id = reserve() else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    fill(id, data)
                } else if images.contains(where: { $0.id == id }) {
                    remove(id)
                    error = "Could not load that photo."
                }
            }
        }
    }

    /// The clipboard's image, if it holds one (iOS asks the user before an app reads it).
    func paste() {
        guard UIPasteboard.general.hasImages, let image = UIPasteboard.general.image else {
            error = "There is no image on the clipboard."
            return
        }
        add(image)
    }

    func remove(_ id: UUID) { images.removeAll { $0.id == id } }

    func clear() { images = [] }

    /// Images a failed send gave back.
    func restore(_ attachments: [ImageAttachment]) {
        for a in attachments where slotsLeft > 0 {
            let thumb = Data(base64Encoded: a.base64).flatMap { ImageEncoder.thumbnail($0) }.map { UIImage(cgImage: $0) }
            images.append(PendingImage(thumbnail: thumb, attachment: a))
        }
    }

    private func finish(_ id: UUID, _ result: Result<ImageAttachment, ImageEncodingError>) {
        guard let index = images.firstIndex(where: { $0.id == id }) else { return }
        switch result {
        case let .success(attachment): images[index].attachment = attachment
        case let .failure(failure):
            images.remove(at: index)
            error = failure.description
        }
    }
}

/// The attached images over the composer, each with a remove button.
struct AttachmentStrip: View {
    var attachments: Attachments

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(attachments.images) { image in
                    ZStack(alignment: .topTrailing) {
                        Group {
                            if let thumb = image.thumbnail {
                                Image(uiImage: thumb).resizable().scaledToFill()
                            } else {
                                Pi.surface3
                            }
                        }
                        .frame(width: 56, height: 56)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .overlay {
                            if !image.ready { QuadSpinner(size: 14, color: Pi.ink1) }
                        }
                        Button { attachments.remove(image.id) } label: {
                            Image(systemName: "xmark").font(.system(size: 9, weight: .bold)).foregroundStyle(Pi.ink1)
                                .frame(width: 20, height: 20)
                                .background(Circle().fill(Pi.surface3))
                                .overlay(Circle().strokeBorder(Pi.lineStrong, lineWidth: 1))
                        }
                        .frame(width: 32, height: 32, alignment: .topTrailing)
                        .offset(x: 6, y: -6)
                        .accessibilityLabel("Remove image")
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 10)
        }
    }
}

/// The camera, for a photo to attach (`UIImagePickerController`: no library access needed for it).
struct CameraPicker: UIViewControllerRepresentable {
    var taken: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    static var available: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.taken(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}
