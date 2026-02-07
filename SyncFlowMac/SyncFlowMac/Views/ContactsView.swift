//
//  ContactsView.swift
//  SyncFlowMac
//
//  View for browsing and calling contacts from Android phone
//

import SwiftUI
// FirebaseDatabase - using FirebaseStubs.swift
import Combine

enum PhoneType: String, CaseIterable {
    case mobile = "Mobile"
    case home = "Home"
    case work = "Work"
    case main = "Main"
    case other = "Other"
    
    var displayName: String {
        return rawValue
    }
}

struct ContactsView: View {
    @EnvironmentObject var appState: AppState
    @StateObject private var contactsStore = ContactsStore()
    @State private var searchText = ""
    @State private var selectedContact: Contact?
    @State private var showNewContactSheet = false
    @State private var editingContact: Contact? = nil

    var filteredSyncedContacts: [Contact] {
        let source = contactsStore.syncedContacts
        guard !searchText.isEmpty else { return source }
        return source.filter { matchesSearch(contact: $0) }
    }

    var filteredPendingContacts: [Contact] {
        let source = contactsStore.pendingContacts
        guard !searchText.isEmpty else { return source }
        return source.filter { matchesSearch(contact: $0) }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header with search and new contact button
            HStack {
                // Search bar
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.secondary)
                    TextField("Search contacts", text: $searchText)
                        .textFieldStyle(.plain)
                    if !searchText.isEmpty {
                        Button(action: { searchText = "" }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(12)
                .background(Color(nsColor: .controlBackgroundColor))
                .cornerRadius(8)

                // New contact button
                Button(action: { showNewContactSheet = true }) {
                    Label("New Contact", systemImage: "person.badge.plus")
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            Divider()

            // Contacts list
            if contactsStore.isLoading {
                VStack {
                    ProgressView()
                    Text("Loading contacts...")
                        .foregroundColor(.secondary)
                        .padding(.top)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredSyncedContacts.isEmpty && filteredPendingContacts.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: searchText.isEmpty ? "person.2.slash" : "magnifyingglass")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text(searchText.isEmpty ? "No contacts synced" : "No contacts found")
                        .font(.title3)
                        .foregroundColor(.secondary)
                    if searchText.isEmpty {
                        Text("Contacts from your Android phone will appear here.\nYou can also create new contacts that sync to your phone.")
                            .font(.body)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)

                        Button(action: { showNewContactSheet = true }) {
                            Label("Create New Contact", systemImage: "person.badge.plus")
                        }
                        .buttonStyle(.borderedProminent)
                        .padding(.top)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 12, pinnedViews: [.sectionHeaders]) {
                        if !filteredPendingContacts.isEmpty {
                            Section {
                                ForEach(filteredPendingContacts) { contact in
                                    PendingContactRow(
                                        contact: contact,
                                        onEdit: { editingContact = contact },
                                        onDelete: { deleteContact(contact) }
                                    )
                                    .environmentObject(appState)
                                }
                            } header: {
                                HStack(spacing: 12) {
                                    HStack(spacing: 8) {
                                        Image(systemName: "laptopcomputer")
                                            .font(.system(size: 14, weight: .semibold))
                                            .foregroundColor(.blue)
                                        Text("Pending changes")
                                            .font(.system(.headline, design: .default))
                                            .foregroundColor(.primary)
                                    }
                                    Spacer()
                                    VStack(alignment: .trailing) {
                                        Text("\(filteredPendingContacts.count)")
                                            .font(.system(.caption, design: .rounded))
                                            .fontWeight(.semibold)
                                            .foregroundColor(.white)
                                            .frame(minWidth: 24)
                                            .padding(.vertical, 2)
                                            .padding(.horizontal, 8)
                                            .background(Color.blue)
                                            .cornerRadius(6)
                                    }
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)
                                .background(Color(nsColor: .windowBackgroundColor))
                            }
                        }

                        if !filteredSyncedContacts.isEmpty {
                            Section {
                                ForEach(filteredSyncedContacts) { contact in
                                    ContactRow(contact: contact, selectedContact: $selectedContact, contactsStore: contactsStore)
                                        .environmentObject(appState)
                                }
                            } header: {
                                HStack(spacing: 12) {
                                    HStack(spacing: 8) {
                                        Image(systemName: "iphone")
                                            .font(.system(size: 14, weight: .semibold))
                                            .foregroundColor(.green)
                                        Text("From Android")
                                            .font(.system(.headline, design: .default))
                                            .foregroundColor(.primary)
                                    }
                                    Spacer()
                                    VStack(alignment: .trailing) {
                                        Text("\(filteredSyncedContacts.count)")
                                            .font(.system(.caption, design: .rounded))
                                            .fontWeight(.semibold)
                                            .foregroundColor(.white)
                                            .frame(minWidth: 24)
                                            .padding(.vertical, 2)
                                            .padding(.horizontal, 8)
                                            .background(Color.green)
                                            .cornerRadius(6)
                                    }
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)
                                .background(Color(nsColor: .windowBackgroundColor))
                            }
                        }
                    }
                    .padding(.vertical, 12)
                    .padding(.horizontal, 4)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor))
        .onAppear {
            if let userId = appState.userId {
                contactsStore.startListening(userId: userId)
            }
        }
        .onChange(of: appState.userId) { newUserId in
            // Start listener when userId becomes available (e.g., after pairing)
            if let userId = newUserId {
                contactsStore.startListening(userId: userId)
            } else {
                contactsStore.stopListening()
            }
        }
        .sheet(isPresented: $showNewContactSheet) {
            ContactEditSheet(
                mode: .create,
                onSave: { _, name, phone, phoneType, email, notes in
                    Task {
                        await createContact(name: name, phone: phone, phoneType: phoneType, email: email, notes: notes)
                    }
                }
            )
        }
        .sheet(item: $editingContact) { contact in
            ContactEditSheet(
                mode: .edit(contact),
                onSave: { contactId, name, phone, phoneType, email, notes in
                    Task {
                        await updateContact(contactId: contactId ?? contact.id, name: name, phone: phone, phoneType: phoneType, email: email, notes: notes)
                    }
                }
            )
        }
    }

    private func createContact(name: String, phone: String, phoneType: String, email: String?, notes: String?) async {
        // TODO: Add VPS contact creation endpoint
        print("[ContactsView] Contact creation not yet available via VPS")
    }

    private func updateContact(contactId: String, name: String, phone: String, phoneType: String, email: String?, notes: String?) async {
        // TODO: Add VPS contact update endpoint
        print("[ContactsView] Contact update not yet available via VPS")
    }

    private func deleteContact(_ contact: Contact) {
        // TODO: Add VPS contact deletion endpoint
        print("[ContactsView] Contact deletion not yet available via VPS")
    }

    private func matchesSearch(contact: Contact) -> Bool {
        if searchText.isEmpty {
            return true
        }

        if contact.displayName.localizedCaseInsensitiveContains(searchText) {
            return true
        }

        let phone = contact.phoneNumber ?? ""
        if phone.localizedCaseInsensitiveContains(searchText) {
            return true
        }

        let queryDigits = searchText.filter { $0.isNumber }
        if queryDigits.isEmpty {
            return false
        }

        let normalized = contact.normalizedNumber ?? phone
        let numberDigits = normalized.filter { $0.isNumber }
        if numberDigits.contains(queryDigits) {
            return true
        }

        return !numberDigits.isEmpty && queryDigits.contains(numberDigits)
    }
}

// MARK: - Contact Row

struct ContactRow: View {
    let contact: Contact
    @Binding var selectedContact: Contact?
    @EnvironmentObject var appState: AppState
    @ObservedObject var contactsStore: ContactsStore  // BANDWIDTH OPTIMIZATION: Use shared cache

    @State private var isHovered = false
    @State private var showCallAlert = false
    @State private var callStatus: CallRequestStatus? = nil
    @State private var isCallInProgress = false
    @State private var selectedSim: SimInfo? = nil

    // BANDWIDTH OPTIMIZATION: Use cached SIMs and devices from contactsStore
    // instead of loading them per-row (saves ~2 fetches per contact hover)
    private var availableSims: [SimInfo] { contactsStore.cachedSims }
    private var pairedDevices: [SyncFlowDevice] { contactsStore.cachedDevices }

    var body: some View {
        HStack(spacing: 16) {
            // Avatar with photo or initials - larger size
            Group {
                if let photoBase64 = contact.photoBase64,
                   let imageData = Data(base64Encoded: photoBase64, options: .ignoreUnknownCharacters),
                   let nsImage = NSImage(data: imageData) {
                    Image(nsImage: nsImage)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 56, height: 56)
                        .clipShape(Circle())
                        .shadow(radius: 2)
                } else {
                    Circle()
                        .fill(
                            LinearGradient(
                                gradient: Gradient(colors: [
                                    Color.blue.opacity(0.3),
                                    Color.cyan.opacity(0.2)
                                ]),
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 56, height: 56)
                        .overlay(
                            Text(contact.initials)
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundColor(.blue)
                        )
                }
            }

            // Contact info - improved layout
            VStack(alignment: .leading, spacing: 6) {
                Text(contact.displayName)
                    .font(.system(.body, design: .default))
                    .fontWeight(.semibold)
                    .foregroundColor(.primary)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        Image(systemName: "phone.fill")
                            .font(.system(size: 10))
                            .foregroundColor(.secondary)
                        Text(contact.formattedPhoneNumber)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }

                    if !contact.phoneType.isEmpty {
                        HStack(spacing: 4) {
                            Image(systemName: "tag.fill")
                                .font(.system(size: 10))
                                .foregroundColor(.secondary)
                            Text(contact.phoneType)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }

            Spacer()

            // Action buttons - redesigned
            if isHovered {
                HStack(spacing: 8) {
                    if availableSims.count > 1 {
                        Menu {
                            ForEach(availableSims) { sim in
                                Button(action: {
                                    selectedSim = sim
                                    initiateCall()
                                }) {
                                    Text(sim.formattedDisplayName)
                                }
                            }
                        } label: {
                            Image(systemName: isCallInProgress ? "phone.fill.arrow.up.right" : "phone.fill")
                                .foregroundColor(isCallInProgress ? .green : .blue)
                        }
                        .buttonStyle(.bordered)
                        .help("Choose SIM card to call from")
                        .disabled(isCallInProgress)
                    } else {
                        Button(action: {
                            initiateCall()
                        }) {
                            Image(systemName: isCallInProgress ? "phone.fill.arrow.up.right" : "phone.fill")
                                .foregroundColor(isCallInProgress ? .green : .blue)
                        }
                        .buttonStyle(.bordered)
                        .help("Call via Android phone")
                        .disabled(isCallInProgress)
                    }

                    // SyncFlow Video Call button
                    Button(action: {
                        initiateSyncFlowCall(isVideo: true)
                    }) {
                        Image(systemName: "video.fill")
                            .foregroundColor(.green)
                    }
                    .buttonStyle(.bordered)
                    .help("SyncFlow video call to Android device")

                    // Message button
                    Button(action: {
                        startConversation()
                    }) {
                        Image(systemName: "message.fill")
                            .foregroundColor(.blue)
                    }
                    .buttonStyle(.bordered)
                    .help("Send message")
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(isHovered ? Color(nsColor: .controlBackgroundColor).opacity(0.5) : Color.clear)
        .cornerRadius(8)
        .onHover { hovering in
            isHovered = hovering
            // BANDWIDTH OPTIMIZATION: Load SIMs and devices once at app level (not per-row)
            // This saves ~2 Firebase fetches per contact hover
            if hovering {
                contactsStore.loadSimsIfNeeded()
                contactsStore.loadDevicesIfNeeded()
            }
        }
        .alert("Calling \(contact.displayName)", isPresented: $showCallAlert) {
            Button("OK") {
                showCallAlert = false
                isCallInProgress = false
            }
        } message: {
            if let status = callStatus {
                Text(status.description)
            }
        }
    }

    private func initiateCall() {
        isCallInProgress = true
        appState.makeCall(to: contact.phoneNumber ?? "")

        callStatus = .completed
        showCallAlert = true

        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            showCallAlert = false
            isCallInProgress = false
        }
    }

    private func startConversation() {
        // Create a conversation object for this contact
        let conversation = Conversation(
            id: contact.normalizedNumber ?? "",
            address: contact.phoneNumber ?? "",
            contactName: contact.displayName,
            lastMessage: "",
            timestamp: Date(),
            unreadCount: 0,
            allAddresses: [contact.phoneNumber ?? ""],
            isPinned: false,
            isArchived: false,
            isBlocked: false,
            avatarColor: nil
        )

        // Update app state to show conversation and switch to messages tab
        appState.selectedConversation = conversation
        appState.selectedTab = .messages
    }

    private func initiateSyncFlowCall(isVideo: Bool) {
        // Start user-to-user video call using the contact's phone number
        let phoneNumber = contact.phoneNumber
        let recipientName = contact.displayName

        Task {
            do {
                let callId = try await appState.syncFlowCallManager.startCallToUser(
                    recipientPhoneNumber: phoneNumber ?? "",
                    recipientName: recipientName,
                    isVideo: isVideo
                )
                print("Started \(isVideo ? "video" : "audio") call to \(recipientName): \(callId)")

                // Show the call view
                await MainActor.run {
                    appState.showSyncFlowCallView = true
                }
            } catch {
                print("Failed to start call: \(error.localizedDescription)")
            }
        }
    }
}

// MARK: - Pending Contact Row

struct PendingContactRow: View {
    let contact: Contact
    let onEdit: () -> Void
    let onDelete: () -> Void
    @EnvironmentObject var appState: AppState

    @State private var isHovered = false
    @State private var showDeleteConfirmation = false

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color.blue.opacity(0.2))
                .frame(width: 44, height: 44)
                .overlay(
                    Text(contact.initials)
                        .font(.headline)
                        .foregroundColor(.blue)
                )

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(contact.displayName)
                        .font(.body)
                        .fontWeight(.medium)

                    Image(systemName: contact.isPendingSync ? "arrow.triangle.2.circlepath" : "checkmark.circle.fill")
                        .foregroundColor(contact.isPendingSync ? .orange : .green)
                        .font(.caption)
                        .help(contact.isPendingSync ? "Pending sync to Android" : "Synced to Android")
                }

                HStack(spacing: 6) {
                    Text(contact.formattedPhoneNumber)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text("•")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(contact.phoneType)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    if let email = contact.email, !email.isEmpty {
                        Text("•")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text(email)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                    }
                }
            }

            Spacer()

            if isHovered {
                Button(action: onEdit) {
                    Image(systemName: "pencil")
                        .foregroundColor(.blue)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.borderless)
                .help("Edit contact")

                Button(action: { showDeleteConfirmation = true }) {
                    Image(systemName: "trash")
                        .foregroundColor(.red)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.borderless)
                .help("Delete contact")

                Button(action: {
                    if let phone = contact.phoneNumber {
                        appState.makeCall(to: phone)
                    }
                }) {
                    Image(systemName: "phone.fill")
                        .foregroundColor(.green)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.borderless)
                .help("Call contact")

                Button(action: {
                    let conversation = Conversation(
                        id: contact.normalizedNumber ?? (contact.phoneNumber ?? ""),
                        address: contact.phoneNumber ?? "",
                        contactName: contact.displayName,
                        lastMessage: "",
                        timestamp: Date(),
                        unreadCount: 0,
                        allAddresses: [contact.phoneNumber ?? ""],
                        isPinned: false,
                        isArchived: false,
                        isBlocked: false,
                        avatarColor: nil
                    )
                    appState.selectedConversation = conversation
                    appState.selectedTab = .messages
                }) {
                    Image(systemName: "message.fill")
                        .foregroundColor(.blue)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.borderless)
                .help("Send message")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(isHovered ? Color(nsColor: .controlBackgroundColor) : Color.clear)
        .onHover { hovering in
            isHovered = hovering
        }
        .alert("Delete Contact", isPresented: $showDeleteConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                onDelete()
            }
        } message: {
            Text("Are you sure you want to delete \"\(contact.displayName)\"? This will also remove it from your Android phone.")
        }
    }
}

// MARK: - Contact Edit Sheet

enum ContactEditMode {
    case create
    case edit(Contact)
}

struct ContactEditSheet: View {
    let mode: ContactEditMode
    let onSave: (_ contactId: String?, _ name: String, _ phone: String, _ phoneType: String, _ email: String?, _ notes: String?) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var name: String = ""
    @State private var phoneNumber: String = ""
    @State private var phoneType: PhoneType = .mobile
    @State private var email: String = ""
    @State private var notes: String = ""
    @State private var isSaving = false

    var isValid: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty &&
        !phoneNumber.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var title: String {
        switch mode {
        case .create: return "New Contact"
        case .edit: return "Edit Contact"
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Button("Cancel") {
                    dismiss()
                }
                .keyboardShortcut(.escape)

                Spacer()

                Text(title)
                    .font(.headline)

                Spacer()

                Button("Save") {
                    saveContact()
                }
                .keyboardShortcut(.return)
                .disabled(!isValid || isSaving)
            }
            .padding()

            Divider()

            // Form
            Form {
                Section {
                    TextField("Name", text: $name)
                        .textFieldStyle(.roundedBorder)

                    TextField("Phone Number", text: $phoneNumber)
                        .textFieldStyle(.roundedBorder)

                    Picker("Phone Type", selection: $phoneType) {
                        ForEach(PhoneType.allCases, id: \.self) { type in
                            Text(type.displayName).tag(type)
                        }
                    }
                }

                Section {
                    TextField("Email (optional)", text: $email)
                        .textFieldStyle(.roundedBorder)

                    TextField("Notes (optional)", text: $notes, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(3...6)
                }

                Section {
                    HStack {
                        Image(systemName: "info.circle")
                            .foregroundColor(.blue)
                        Text("This contact will automatically sync to your Android phone.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding()
        }
        .frame(width: 400, height: 380)
        .onAppear {
            if case .edit(let contact) = mode {
                name = contact.displayName
                phoneNumber = contact.phoneNumber ?? ""
                phoneType = PhoneType(rawValue: contact.phoneType) ?? .mobile
                email = contact.email ?? ""
                notes = contact.notes ?? ""
            }
        }
    }

    private func saveContact() {
        isSaving = true

        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let trimmedPhone = phoneNumber.trimmingCharacters(in: .whitespaces)
        let trimmedEmail = email.trimmingCharacters(in: .whitespaces)
        let trimmedNotes = notes.trimmingCharacters(in: .whitespaces)

        let contactId: String?
        switch mode {
        case .create:
            contactId = nil
        case .edit(let contact):
            contactId = contact.id
        }

        onSave(
            contactId,
            trimmedName,
            trimmedPhone,
            phoneType.rawValue,
            trimmedEmail.isEmpty ? nil : trimmedEmail,
            trimmedNotes.isEmpty ? nil : trimmedNotes
        )

        dismiss()
    }
}

// MARK: - Contacts Store

class ContactsStore: ObservableObject {
    @Published var contacts: [Contact] = []
    @Published var isLoading = true

    // Cache SIMs and devices at app level (not per-row)
    @Published var cachedSims: [SimInfo] = []
    @Published var cachedDevices: [SyncFlowDevice] = []
    private var hasLoadedSims = false
    private var hasLoadedDevices = false

    private var currentUserId: String?
    private var vpsCancellables = Set<AnyCancellable>()

    /// Load SIMs once and cache them
    func loadSimsIfNeeded() {
        guard !hasLoadedSims else { return }
        hasLoadedSims = true
        // SIMs not yet available via VPS - will be empty
    }

    /// Load devices once and cache them
    func loadDevicesIfNeeded() {
        guard !hasLoadedDevices else { return }
        hasLoadedDevices = true

        Task {
            do {
                let response = try await VPSService.shared.getDevices()
                await MainActor.run {
                    self.cachedDevices = response.devices.map { device in
                        SyncFlowDevice(
                            id: device.id,
                            name: device.name ?? "Unknown",
                            platform: device.deviceType,
                            online: true,
                            lastSeen: Date()
                        )
                    }
                }
            } catch {
                print("[ContactsStore] Error loading devices: \(error)")
                hasLoadedDevices = false
            }
        }
    }

    var pendingContacts: [Contact] {
        contacts.filter { $0.isPendingSync }
    }

    var syncedContacts: [Contact] {
        contacts.filter { !$0.isPendingSync }
    }

    func startListening(userId: String) {
        currentUserId = userId
        isLoading = true

        stopListening()

        // Subscribe to real-time contact events via WebSocket
        VPSService.shared.contactAdded
            .receive(on: DispatchQueue.main)
            .sink { [weak self] vpsContact in
                guard let self = self else { return }
                let contact = self.convertVPSContact(vpsContact)
                if !self.contacts.contains(where: { $0.id == contact.id }) {
                    self.contacts.append(contact)
                    self.contacts.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
                }
            }
            .store(in: &vpsCancellables)

        VPSService.shared.contactUpdated
            .receive(on: DispatchQueue.main)
            .sink { [weak self] vpsContact in
                guard let self = self else { return }
                let contact = self.convertVPSContact(vpsContact)
                if let index = self.contacts.firstIndex(where: { $0.id == contact.id }) {
                    self.contacts[index] = contact
                }
            }
            .store(in: &vpsCancellables)

        VPSService.shared.contactDeleted
            .receive(on: DispatchQueue.main)
            .sink { [weak self] contactId in
                guard let self = self else { return }
                self.contacts.removeAll { $0.id == contactId }
            }
            .store(in: &vpsCancellables)

        // Fetch initial contacts from VPS REST API
        Task {
            do {
                let response = try await VPSService.shared.getContacts(limit: 500)
                let fetchedContacts = response.contacts.map { self.convertVPSContact($0) }

                await MainActor.run {
                    self.contacts = fetchedContacts.sorted {
                        $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
                    }
                    self.isLoading = false
                    print("[ContactsStore VPS] Loaded \(fetchedContacts.count) contacts")
                }
            } catch {
                print("[ContactsStore VPS] Error loading contacts: \(error.localizedDescription)")
                await MainActor.run {
                    self.isLoading = false
                }
            }
        }
    }

    private func convertVPSContact(_ vpsContact: VPSContact) -> Contact {
        let defaultSync = Contact.SyncMetadata(
            lastUpdatedAt: Date().timeIntervalSince1970 * 1000,
            lastSyncedAt: Date().timeIntervalSince1970 * 1000,
            lastUpdatedBy: "android",
            version: 1,
            pendingAndroidSync: false,
            desktopOnly: false
        )

        return Contact(
            id: vpsContact.id,
            displayName: vpsContact.displayName ?? "Unknown",
            phoneNumber: vpsContact.phoneNumbers?.first,
            normalizedNumber: vpsContact.phoneNumbers?.first,
            phoneType: "Mobile",
            photoBase64: vpsContact.photoThumbnail,
            notes: nil,
            email: vpsContact.emails?.first,
            sync: defaultSync
        )
    }

    func stopListening() {
        vpsCancellables.removeAll()
    }

    deinit {
        stopListening()
    }
}
