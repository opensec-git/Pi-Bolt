import SwiftUI

/// The Quad spinner (android `QuadSpinner`): a 3x3 grid of cells. While it works a diagonal band of light moves
/// through the cells every 120 ms; at rest (or with Reduce Motion) the cells sit at a still frame.
struct QuadSpinner: View {
    var size: CGFloat = 16
    var color: Color = Pi.ink1
    var animate = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if animate && !reduceMotion {
            TimelineView(.periodic(from: .now, by: 0.12)) { context in
                grid(tick: Int(context.date.timeIntervalSinceReferenceDate / 0.12))
            }
            .accessibilityLabel("Working")
        } else {
            grid(tick: nil).accessibilityLabel("Idle")
        }
    }

    private func grid(tick: Int?) -> some View {
        Canvas { context, canvas in
            let unit = min(canvas.width, canvas.height) / 11
            let ox = (canvas.width - 11 * unit) / 2
            let oy = (canvas.height - 11 * unit) / 2
            for i in 0 ..< 9 {
                let row = i / 3
                let column = i % 3
                let alpha: Double
                if let tick {
                    // The band: cells on the anti-diagonal (row + column) nearest the moving phase are lit.
                    let phase = tick % 6
                    let distance = abs((row + column) - phase)
                    alpha = distance == 0 ? 1 : (distance == 1 ? 0.45 : 0.15)
                } else {
                    alpha = (row + column) % 2 == 0 ? 0.7 : 0.25
                }
                let rect = CGRect(x: ox + CGFloat(column) * 4 * unit, y: oy + CGFloat(row) * 4 * unit, width: 3 * unit, height: 3 * unit)
                context.fill(Path(roundedRect: rect, cornerRadius: unit * 0.6), with: .color(color.opacity(alpha)))
            }
        }
        .frame(width: size, height: size)
    }
}
