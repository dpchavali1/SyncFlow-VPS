'use client'

import React, { useState } from 'react'
import { X, ChevronLeft, ChevronRight, Download } from 'lucide-react'

interface Photo {
  id: string
  fileName: string
  dateTaken: number
  size: number
  width: number
  height: number
  mimeType: string
  uploadUrl?: string
  base64Data?: string
}

interface PhotoGalleryProps {
  photos: Photo[]
  isLoading?: boolean
  onDelete?: (photoId: string) => Promise<void>
}

export function PhotoGallery({ photos, isLoading = false, onDelete }: PhotoGalleryProps) {
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null)
  const [deleting, setDeleting] = useState<string | null>(null)

  if (photos.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-gray-500">
        <div className="text-4xl mb-4">📸</div>
        <p>No photos synced yet</p>
        <p className="text-sm mt-1">Photos from your phone will appear here</p>
      </div>
    )
  }

  const formatDate = (timestamp: number) => {
    return new Date(timestamp).toLocaleDateString([], {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    })
  }

  const formatSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes}B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`
    return `${(bytes / (1024 * 1024)).toFixed(1)}MB`
  }

  const handleDelete = async (photoId: string) => {
    if (!onDelete) return
    if (!confirm('Delete this photo?')) return

    setDeleting(photoId)
    try {
      await onDelete(photoId)
    } finally {
      setDeleting(null)
    }
  }

  const selectedPhoto = selectedIndex !== null ? photos[selectedIndex] : null

  return (
    <div className="space-y-6">
      {/* Grid View */}
      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
        {photos.map((photo, index) => (
          <div
            key={photo.id}
            className="group relative aspect-square rounded-lg overflow-hidden bg-gray-100 cursor-pointer hover:shadow-lg transition-shadow"
            onClick={() => setSelectedIndex(index)}
          >
            {/* Placeholder or actual image */}
            {photo.base64Data ? (
              <img
                src={`data:${photo.mimeType};base64,${photo.base64Data}`}
                alt={photo.fileName}
                className="w-full h-full object-cover group-hover:scale-105 transition-transform"
              />
            ) : (
              <div className="w-full h-full flex items-center justify-center bg-gray-200">
                <span className="text-4xl">📷</span>
              </div>
            )}

            {/* Overlay info */}
            <div className="absolute inset-0 bg-black/0 group-hover:bg-black/50 transition-colors flex items-end p-2">
              <div className="text-white text-xs opacity-0 group-hover:opacity-100 transition-opacity">
                {formatDate(photo.dateTaken)}
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Lightbox Modal */}
      {selectedPhoto && (
        <div className="fixed inset-0 bg-black/90 z-50 flex items-center justify-center p-4">
          {/* Header */}
          <button
            onClick={() => setSelectedIndex(null)}
            className="absolute top-4 right-4 p-2 hover:bg-white/10 rounded-lg text-white transition-colors"
          >
            <X className="w-6 h-6" />
          </button>

          {/* Main Image */}
          <div className="flex items-center justify-center max-w-4xl max-h-[80vh]">
            {selectedPhoto.base64Data ? (
              <img
                src={`data:${selectedPhoto.mimeType};base64,${selectedPhoto.base64Data}`}
                alt={selectedPhoto.fileName}
                className="max-w-full max-h-full object-contain"
              />
            ) : (
              <div className="flex flex-col items-center text-white">
                <span className="text-8xl mb-4">📷</span>
                <p className="text-lg">{selectedPhoto.fileName}</p>
              </div>
            )}
          </div>

          {/* Navigation */}
          {photos.length > 1 && (
            <>
              <button
                onClick={() =>
                  setSelectedIndex(
                    selectedIndex === 0
                      ? photos.length - 1
                      : (selectedIndex ?? 0) - 1
                  )
                }
                className="absolute left-4 p-2 hover:bg-white/10 rounded-lg text-white transition-colors"
              >
                <ChevronLeft className="w-8 h-8" />
              </button>

              <button
                onClick={() =>
                  setSelectedIndex(
                    selectedIndex === photos.length - 1
                      ? 0
                      : (selectedIndex ?? 0) + 1
                  )
                }
                className="absolute right-4 p-2 hover:bg-white/10 rounded-lg text-white transition-colors"
              >
                <ChevronRight className="w-8 h-8" />
              </button>
            </>
          )}

          {/* Info and Actions */}
          <div className="absolute bottom-4 left-4 right-4 bg-white/10 backdrop-blur rounded-lg p-4 text-white">
            <div className="flex items-start justify-between mb-3">
              <div className="flex-1">
                <p className="font-medium">{selectedPhoto.fileName}</p>
                <p className="text-sm text-gray-300 mt-1">
                  {formatDate(selectedPhoto.dateTaken)} • {formatSize(selectedPhoto.size)}
                </p>
                {selectedPhoto.width && selectedPhoto.height && (
                  <p className="text-sm text-gray-300">
                    {selectedPhoto.width} × {selectedPhoto.height}px
                  </p>
                )}
              </div>

              {/* Action Buttons */}
              <div className="flex gap-2 ml-4">
                {selectedPhoto.uploadUrl && (
                  <a
                    href={selectedPhoto.uploadUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="p-2 hover:bg-white/20 rounded-lg transition-colors"
                    title="Download"
                  >
                    <Download className="w-5 h-5" />
                  </a>
                )}

                {onDelete && (
                  <button
                    onClick={() => handleDelete(selectedPhoto.id)}
                    disabled={deleting === selectedPhoto.id}
                    className="p-2 hover:bg-red-500/20 rounded-lg transition-colors disabled:opacity-50"
                    title="Delete"
                  >
                    <X className="w-5 h-5" />
                  </button>
                )}
              </div>
            </div>

            {/* Progress indicator */}
            <div className="flex items-center justify-between text-sm">
              <span>
                {selectedIndex !== null && `${selectedIndex + 1} / ${photos.length}`}
              </span>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
