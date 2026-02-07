//
//  LabelManagerView.swift
//  SyncFlowMac
//
//  Views for managing conversation labels/folders
//

import SwiftUI

// MARK: - Label Picker (for assigning labels to conversations)

struct LabelPicker: View {
    let address: String
    let contactName: String?
    @Environment(\.dismiss) private var dismiss

    private let preferences = PreferencesService.shared
    @State private var labels: [PreferencesService.ConversationLabel] = []
    @State private var assignedLabelIds: Set<String> = []
    @State private var showCreateLabel = false

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Labels")
                        .font(.title3)
                        .fontWeight(.semibold)

                    Text(contactName ?? address)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                Button("Done") {
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            Divider()

            // Labels list
            List {
                ForEach(labels) { label in
                    LabelRow(
                        label: label,
                        isSelected: assignedLabelIds.contains(label.id),
                        onToggle: {
                            toggleLabel(label.id)
                        }
                    )
                }

                Button(action: { showCreateLabel = true }) {
                    HStack {
                        Image(systemName: "plus.circle")
                            .foregroundColor(.accentColor)
                        Text("Create New Label")
                            .foregroundColor(.accentColor)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .frame(width: 300, height: 350)
        .onAppear {
            loadData()
        }
        .sheet(isPresented: $showCreateLabel) {
            CreateLabelView(onCreated: { newLabel in
                labels.append(newLabel)
            })
        }
    }

    private func loadData() {
        labels = preferences.getLabels()
        let assignments = preferences.getLabelAssignments()
        assignedLabelIds = Set(assignments[address] ?? [])
    }

    private func toggleLabel(_ labelId: String) {
        if assignedLabelIds.contains(labelId) {
            assignedLabelIds.remove(labelId)
            preferences.removeLabel(labelId, from: address)
        } else {
            assignedLabelIds.insert(labelId)
            preferences.addLabel(labelId, to: address)
        }
    }
}

struct LabelRow: View {
    let label: PreferencesService.ConversationLabel
    let isSelected: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 12) {
                // Color dot
                Circle()
                    .fill(Color(hex: label.color))
                    .frame(width: 12, height: 12)

                // Icon and name
                Image(systemName: label.icon)
                    .foregroundColor(Color(hex: label.color))

                Text(label.name)
                    .foregroundColor(.primary)

                Spacer()

                // Checkbox
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(isSelected ? .accentColor : .secondary)
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Create Label View

struct CreateLabelView: View {
    let onCreated: (PreferencesService.ConversationLabel) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var selectedColor = "#2196F3"
    @State private var selectedIcon = "tag.fill"

    private let preferences = PreferencesService.shared

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("Create Label")
                    .font(.title3)
                    .fontWeight(.semibold)

                Spacer()

                Button("Cancel") {
                    dismiss()
                }
            }
            .padding()

            Divider()

            Form {
                // Name
                Section {
                    TextField("Label Name", text: $name)
                } header: {
                    Text("Name")
                }

                // Color
                Section {
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(30)), count: 6), spacing: 10) {
                        ForEach(PreferencesService.ConversationLabel.availableColors, id: \.self) { color in
                            Circle()
                                .fill(Color(hex: color))
                                .frame(width: 24, height: 24)
                                .overlay(
                                    Circle()
                                        .stroke(selectedColor == color ? Color.primary : Color.clear, lineWidth: 2)
                                )
                                .onTapGesture {
                                    selectedColor = color
                                }
                        }
                    }
                } header: {
                    Text("Color")
                }

                // Icon
                Section {
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(40)), count: 4), spacing: 10) {
                        ForEach(PreferencesService.ConversationLabel.availableIcons, id: \.self) { icon in
                            Image(systemName: icon)
                                .font(.title3)
                                .foregroundColor(selectedIcon == icon ? Color(hex: selectedColor) : .secondary)
                                .frame(width: 32, height: 32)
                                .background(
                                    RoundedRectangle(cornerRadius: 6)
                                        .fill(selectedIcon == icon ? Color(hex: selectedColor).opacity(0.2) : Color.clear)
                                )
                                .onTapGesture {
                                    selectedIcon = icon
                                }
                        }
                    }
                } header: {
                    Text("Icon")
                }

                // Preview
                Section {
                    HStack(spacing: 8) {
                        Circle()
                            .fill(Color(hex: selectedColor))
                            .frame(width: 12, height: 12)
                        Image(systemName: selectedIcon)
                            .foregroundColor(Color(hex: selectedColor))
                        Text(name.isEmpty ? "Label Name" : name)
                            .foregroundColor(name.isEmpty ? .secondary : .primary)
                    }
                } header: {
                    Text("Preview")
                }
            }
            .formStyle(.grouped)

            Divider()

            // Create button
            HStack {
                Spacer()
                Button("Create") {
                    let label = preferences.createLabel(name: name, color: selectedColor, icon: selectedIcon)
                    onCreated(label)
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .disabled(name.isEmpty)
            }
            .padding()
        }
        .frame(width: 350, height: 500)
    }
}

// MARK: - Label Badge (for displaying on conversations)

struct LabelBadge: View {
    let label: PreferencesService.ConversationLabel

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(Color(hex: label.color))
                .frame(width: 6, height: 6)

            Text(label.name)
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .background(Color(hex: label.color).opacity(0.1))
        .cornerRadius(4)
    }
}

// MARK: - Label Filter Chip

struct LabelFilterChip: View {
    let label: PreferencesService.ConversationLabel
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 4) {
                Image(systemName: label.icon)
                    .font(.caption)
                Text(label.name)
                    .font(.caption)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(isSelected ? Color(hex: label.color) : Color.clear)
            .foregroundColor(isSelected ? .white : Color(hex: label.color))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color(hex: label.color) ?? .blue, lineWidth: 1)
            )
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Labels Management View (for Settings)

struct LabelsManagementView: View {
    private let preferences = PreferencesService.shared
    @State private var labels: [PreferencesService.ConversationLabel] = []
    @State private var showCreateLabel = false
    @State private var editingLabel: PreferencesService.ConversationLabel?

    var body: some View {
        Form {
            Section {
                ForEach(labels) { label in
                    HStack(spacing: 12) {
                        Circle()
                            .fill(Color(hex: label.color))
                            .frame(width: 12, height: 12)

                        Image(systemName: label.icon)
                            .foregroundColor(Color(hex: label.color))

                        Text(label.name)

                        Spacer()

                        // Conversation count
                        let count = preferences.getConversations(with: label.id).count
                        Text("\(count)")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 2)
                            .background(Color.secondary.opacity(0.2))
                            .cornerRadius(8)

                        // Delete button
                        Button(action: {
                            preferences.deleteLabel(id: label.id)
                            loadLabels()
                        }) {
                            Image(systemName: "trash")
                                .foregroundColor(.red)
                        }
                        .buttonStyle(.plain)
                    }
                }

                Button(action: { showCreateLabel = true }) {
                    HStack {
                        Image(systemName: "plus.circle")
                        Text("Create New Label")
                    }
                }
            } header: {
                Text("Labels")
            } footer: {
                Text("Create labels to organize your conversations")
            }
        }
        .formStyle(.grouped)
        .onAppear {
            loadLabels()
        }
        .sheet(isPresented: $showCreateLabel) {
            CreateLabelView(onCreated: { _ in
                loadLabels()
            })
        }
    }

    private func loadLabels() {
        labels = preferences.getLabels()
    }
}

// Color extension is defined in ConversationListView.swift
