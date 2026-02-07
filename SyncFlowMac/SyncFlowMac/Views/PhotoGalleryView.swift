//
//  PhotoGalleryView.swift
//  SyncFlowMac
//
//  View to display synced photos from Android
//

import SwiftUI

struct PhotoGalleryView: View {
    @ObservedObject var photoService: PhotoSyncService
    @Environment(\.dismiss) var dismiss
    @State private var selectedPhoto: SyncedPhoto?
    @State private var showingPreview = false

    private let columns = [
        GridItem(.adaptive(minimum: 120, maximum: 150), spacing: 8)
    ]

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("Recent Photos")
                    .font(.headline)

                Spacer()

                if let lastSync = photoService.lastSyncTime {
                    Text("Updated \(lastSync, style: .relative) ago")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Button("Close") {
                    dismiss()
                }
                .keyboardShortcut(.escape, modifiers: [])
            }
            .padding()

            Divider()

            if photoService.recentPhotos.isEmpty {
                // Empty state
                VStack(spacing: 16) {
                    Image(systemName: "photo.on.rectangle.angled")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)

                    Text("No Photos Synced")
                        .font(.headline)
                        .foregroundColor(.secondary)

                    Text("Take photos on your Android phone\nand they'll appear here")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                // Photo grid
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(photoService.recentPhotos) { photo in
                            PhotoThumbnailView(photo: photo)
                                .onTapGesture {
                                    selectedPhoto = photo
                                    showingPreview = true
                                }
                                .contextMenu {
                                    Button("Open in Preview") {
                                        photoService.openPhoto(photo)
                                    }
                                    .disabled(!photo.isDownloaded)

                                    Button("Save to Downloads") {
                                        photoService.savePhoto(photo)
                                    }
                                    .disabled(!photo.isDownloaded)

                                    Divider()

                                    Text("Taken: \(photo.formattedDate)")
                                    Text("Size: \(photo.formattedSize)")
                                }
                        }
                    }
                    .padding()
                }
            }
        }
        .frame(minWidth: 400, minHeight: 300)
        .sheet(isPresented: $showingPreview) {
            if let photo = selectedPhoto {
                PhotoPreviewView(photo: photo, photoService: photoService)
            }
        }
    }
}

struct PhotoThumbnailView: View {
    let photo: SyncedPhoto

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color.gray.opacity(0.2))

            if let thumbnail = photo.thumbnail {
                Image(nsImage: thumbnail)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: 120, height: 120)
                    .clipped()
                    .cornerRadius(8)
            } else {
                // Loading placeholder
                VStack(spacing: 8) {
                    ProgressView()
                        .scaleEffect(0.8)

                    Text("Loading...")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
        }
        .frame(width: 120, height: 120)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.gray.opacity(0.3), lineWidth: 1)
        )
    }
}

struct PhotoPreviewView: View {
    let photo: SyncedPhoto
    let photoService: PhotoSyncService
    @Environment(\.dismiss) var dismiss

    var body: some View {
        VStack(spacing: 0) {
            // Toolbar
            HStack {
                Text(photo.fileName)
                    .font(.headline)

                Spacer()

                Button("Open in Preview") {
                    photoService.openPhoto(photo)
                }
                .disabled(!photo.isDownloaded)

                Button("Save") {
                    photoService.savePhoto(photo)
                    dismiss()
                }
                .disabled(!photo.isDownloaded)

                Button("Close") {
                    dismiss()
                }
            }
            .padding()

            Divider()

            // Image
            if let thumbnail = photo.thumbnail {
                Image(nsImage: thumbnail)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding()
            } else {
                ProgressView("Loading...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            Divider()

            // Info
            HStack {
                Label(photo.formattedDate, systemImage: "calendar")
                Spacer()
                Label(photo.formattedSize, systemImage: "doc")
                Spacer()
                Label("\(photo.width) × \(photo.height)", systemImage: "aspectratio")
            }
            .font(.caption)
            .foregroundColor(.secondary)
            .padding()
        }
        .frame(width: 600, height: 500)
    }
}

#Preview {
    PhotoGalleryView(photoService: PhotoSyncService.shared)
}
