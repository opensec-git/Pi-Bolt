import PiBoltProtocol
import SwiftUI

/// The todo extension's list above the composer (`SessionState.todos`): folded, the task in progress and the count
/// done; open, every task. It leaves once a run ends with every task done.
struct TodoPanel: View {
    var todos: TodoState
    @State private var open = false

    var body: some View {
        let tasks = todos.visible
        let done = tasks.filter { $0.status == .completed }.count
        let current = tasks.first { $0.status == .inProgress } ?? tasks.first { $0.status == .pending }
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeOut(duration: 0.18)) { open.toggle() }
            } label: {
                HStack(spacing: 10) {
                    ProgressRing(fraction: tasks.isEmpty ? 0 : Double(done) / Double(tasks.count)).frame(width: 16, height: 16)
                    Text(open || current == nil ? "Tasks" : label(current!))
                        .font(.system(size: 14, weight: .medium)).foregroundStyle(Pi.ink2).lineLimit(1)
                    Spacer(minLength: 4)
                    Text("\(done)/\(tasks.count)").font(.system(size: 13)).foregroundStyle(Pi.ink3).monospacedDigit()
                    Image(systemName: "chevron.up").font(.system(size: 10, weight: .semibold)).foregroundStyle(Pi.ink4)
                        .rotationEffect(.degrees(open ? 180 : 0))
                }
                .frame(minHeight: 40)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Tasks, \(done) of \(tasks.count) done")
            if open {
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(tasks) { task in
                            HStack(alignment: .firstTextBaseline, spacing: 10) {
                                glyph(task.status).frame(width: 16)
                                Text(task.status == .inProgress ? label(task) : task.subject)
                                    .font(.system(size: 14))
                                    .foregroundStyle(task.status == .completed ? Pi.ink4 : Pi.ink1)
                                    .strikethrough(task.status == .completed, color: Pi.ink4)
                            }
                        }
                    }
                    .padding(.bottom, 10)
                }
                .frame(maxHeight: 220)
            }
        }
        .padding(.horizontal, 14)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Pi.surface1))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Pi.line, lineWidth: 1))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    private func label(_ task: TodoTask) -> String {
        task.status == .inProgress ? (task.activeForm ?? task.subject) : task.subject
    }

    @ViewBuilder private func glyph(_ status: TodoStatus) -> some View {
        switch status {
        case .pending: Image(systemName: "circle").font(.system(size: 12)).foregroundStyle(Pi.ink3)
        case .inProgress: QuadSpinner(size: 12, color: Pi.ink1)
        case .completed: Image(systemName: "checkmark.circle.fill").font(.system(size: 12)).foregroundStyle(Pi.success)
        }
    }
}

private struct ProgressRing: View {
    var fraction: Double

    var body: some View {
        ZStack {
            Circle().stroke(Pi.lineStrong, lineWidth: 2)
            Circle().trim(from: 0, to: fraction).stroke(Pi.success, style: StrokeStyle(lineWidth: 2, lineCap: .round)).rotationEffect(.degrees(-90))
        }
    }
}
