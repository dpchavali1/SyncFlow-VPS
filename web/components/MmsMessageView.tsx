'use client'

import React from 'react'
import Image from 'next/image'
import { Download, FileText, Music, Video } from 'lucide-react'

interface MmsAttachment {
  id: string
  contentType: string
  data?: string // Base64 encoded
  fileName?: string
  filePath?: string
}

interface MmsMessageProps {
  messageId: string
  address: string
  body: string
  subject?: string
  timestamp: number
  attachments: MmsAttachment[]
  isSent: boolean
}

export function MmsMessageView({
  messageId,
  address,
  body,
  subject,
  timestamp,
  attachments,
  isSent,
}: MmsMessageProps) {
  const formatTime = (timestamp: number) => {
    return new Date(timestamp).toLocaleTimeString([], {
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  const getAttachmentIcon = (contentType: string) => {
    if (contentType.startsWith('image/')) return null // Image shows preview
    if (contentType.startsWith('video/')) return <Video className="w-5 h-5" />
    if (contentType.startsWith('audio/')) return <Music className="w-5 h-5" />
    return <FileText className="w-5 h-5" />
  }

  const getAttachmentName = (attachment: MmsAttachment): string => {
    if (attachment.fileName) return attachment.fileName

    const types: { [key: string]: string } = {
      'image/jpeg': 'image.jpg',
      'image/png': 'image.png',
      'video/mp4': 'video.mp4',
      'audio/mpeg': 'audio.mp3',
      'audio/wav': 'audio.wav',
    }

    return types[attachment.contentType] || `file_${attachment.id.substring(0, 8)}`
  }

  return (
    <div
      className={`flex flex-col gap-2 max-w-xs ${
        isSent ? 'items-end' : 'items-start'
      }`}
    >
      {/* Subject (if present) */}
      {subject && (
        <div
          className={`px-3 py-2 rounded-lg text-sm font-semibold ${
            isSent
              ? 'bg-blue-200 text-blue-900'
              : 'bg-gray-200 text-gray-900'
          }`}
        >
          📌 {subject}
        </div>
      )}

      {/* Message body */}
      {body && (
        <div
          className={`px-4 py-2 rounded-lg break-words ${
            isSent
              ? 'bg-blue-500 text-white'
              : 'bg-gray-200 text-gray-900'
          }`}
        >
          {body}
        </div>
      )}

      {/* Attachments */}
      {attachments && attachments.length > 0 && (
        <div className="flex flex-col gap-2 w-full">
          {attachments.map((attachment) => (
            <div key={attachment.id} className="space-y-1">
              {/* Image preview */}
              {attachment.contentType.startsWith('image/') && attachment.data && (
                <div className="relative w-full rounded-lg overflow-hidden bg-gray-100">
                  <img
                    src={`data:${attachment.contentType};base64,${attachment.data}`}
                    alt={getAttachmentName(attachment)}
                    className="max-w-xs max-h-48 object-contain"
                  />
                </div>
              )}

              {/* Other attachments */}
              {!attachment.contentType.startsWith('image/') && (
                <div
                  className={`flex items-center gap-3 px-3 py-2 rounded-lg border ${
                    isSent
                      ? 'bg-blue-100 border-blue-300 text-blue-900'
                      : 'bg-gray-100 border-gray-300 text-gray-900'
                  }`}
                >
                  {getAttachmentIcon(attachment.contentType)}
                  <div className="flex-1 truncate text-sm">
                    {getAttachmentName(attachment)}
                  </div>
                  <Download className="w-4 h-4 flex-shrink-0" />
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Timestamp */}
      <span
        className={`text-xs ${
          isSent ? 'text-blue-600' : 'text-gray-500'
        }`}
      >
        {formatTime(timestamp)}
      </span>
    </div>
  )
}
