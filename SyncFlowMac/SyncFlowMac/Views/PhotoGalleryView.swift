//
//  PhotoGalleryView.swift
//  SyncFlowMac
//
//  Photo sync feature has been removed. This stub is kept to prevent
//  compilation errors from any remaining references.
//

import SwiftUI

struct PhotoGalleryView: View {
    @ObservedObject var photoService: PhotoSyncService

    var body: some View {
        Text("Photo sync has been removed.")
            .foregroundColor(.secondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
