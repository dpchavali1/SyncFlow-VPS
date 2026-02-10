'use client'

import { useState, useEffect, useRef, useMemo, useCallback, memo } from 'react'
import { Send, MoreVertical, MessageSquare, Brain, Loader2, Info, X, Image as ImageIcon, Paperclip, AlertTriangle, Check, CheckCheck, Clock, AlertCircle } from 'lucide-react'
import { useAppStore } from '@/lib/store'
import { markMessagesRead, sendSmsFromWeb, sendMmsFromWeb, uploadMmsImage, waitForAuth } from '@/lib/firebase'
import vpsService from '@/lib/vps'
import { format } from 'date-fns'

interface MessageViewProps {
  onOpenAI?: () => void
}

// LRU Cache for phone number normalization (shared concept with ConversationList)
const normalizationCache = new Map<string, string>()
const MAX_CACHE_SIZE = 1000

// Normalize phone number for comparison with caching
function normalizePhoneNumber(address: string): string {
  const cached = normalizationCache.get(address)
  if (cached !== undefined) return cached

  let result: string
  if (address.includes('@') || address.length < 6) {
    result = address.toLowerCase()
  } else {
    const digitsOnly = address.replace(/[^0-9]/g, '')
    result = digitsOnly.length >= 10 ? digitsOnly.slice(-10) : digitsOnly
  }

  // Maintain cache size
  if (normalizationCache.size >= MAX_CACHE_SIZE) {
    const firstKey = normalizationCache.keys().next().value
    if (firstKey) normalizationCache.delete(firstKey)
  }
  normalizationCache.set(address, result)
  return result
}

interface SelectedImage {
  file: File
  preview: string
}

interface MessageBubbleProps {
  msg: any
  isSent: boolean
  readReceipt: any
}

// Memoized message bubble component to prevent unnecessary re-renders
const MessageBubble = memo(function MessageBubble({ msg, isSent, readReceipt }: MessageBubbleProps) {
  const timestamp = format(new Date(msg.date), 'MMM d, h:mm a')
  const readTime =
    readReceipt?.readAt && typeof readReceipt.readAt === 'number'
      ? format(new Date(readReceipt.readAt), 'h:mm a')
      : null
  const imageAttachments = msg.attachments?.filter((att: any) =>
    att.contentType?.startsWith('image/') || att.type === 'image'
  ) || []
  const hasImages = imageAttachments.length > 0

  return (
    <div className={`flex ${isSent ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-md ${isSent ? 'order-2' : 'order-1'}`}>
        <div
          className={`rounded-2xl overflow-hidden ${
            isSent
              ? 'bg-blue-600 text-white'
              : 'bg-white dark:bg-gray-800 text-gray-900 dark:text-white'
          }`}
        >
          {/* MMS Images */}
          {imageAttachments.map((att: any, idx: number) => {
            const imageSrc = att.url

            if (!imageSrc) {
              return (
                <div key={idx} className="p-4 flex items-center gap-2 text-gray-400">
                  <ImageIcon className="w-5 h-5" />
                  <span className="text-sm">Image not available</span>
                </div>
              )
            }

            return (
              <a
                key={idx}
                href={imageSrc}
                target="_blank"
                rel="noopener noreferrer"
                className="block"
              >
                <img
                  src={imageSrc}
                  alt="MMS attachment"
                  className="max-w-full h-auto cursor-pointer hover:opacity-90 transition-opacity"
                  style={{ maxHeight: '300px', objectFit: 'contain' }}
                  onError={(e) => {
                    const target = e.target as HTMLImageElement
                    const parent = target.parentElement

                    if (!parent) return

                    // Remove image element
                    target.remove()

                    // Create error placeholder using DOM methods (XSS-safe)
                    const errorContainer = document.createElement('div')
                    errorContainer.className = 'p-4 flex items-center gap-2 text-gray-400'

                    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
                    svg.setAttribute('class', 'w-5 h-5')
                    svg.setAttribute('fill', 'none')
                    svg.setAttribute('stroke', 'currentColor')
                    svg.setAttribute('viewBox', '0 0 24 24')

                    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path')
                    path.setAttribute('stroke-linecap', 'round')
                    path.setAttribute('stroke-linejoin', 'round')
                    path.setAttribute('stroke-width', '2')
                    path.setAttribute('d', 'M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z')

                    svg.appendChild(path)

                    const text = document.createElement('span')
                    text.className = 'text-sm'
                    text.textContent = 'Image failed to load'  // textContent is XSS-safe

                    errorContainer.appendChild(svg)
                    errorContainer.appendChild(text)
                    parent.appendChild(errorContainer)
                  }}
                />
              </a>
            )
          })}

          {/* Text content */}
          {msg.body && (
            <p className={`whitespace-pre-wrap break-words ${hasImages ? 'p-3' : 'px-4 py-2'}`}>
              {msg.body}
            </p>
          )}

          {/* Show MMS indicator if no body and no displayable images */}
          {!msg.body && msg.isMms && !hasImages && (
            <div className="px-4 py-2 flex items-center gap-2 text-gray-400">
              <ImageIcon className="w-4 h-4" />
              <span className="text-sm italic">MMS message</span>
            </div>
          )}
        </div>
        <div
          className={`flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400 mt-1 ${
            isSent ? 'justify-end' : 'justify-start'
          }`}
        >
          <span>{timestamp}</span>
          {isSent && (
            msg.id.startsWith('optimistic_') || msg.deliveryStatus === 'sending' ? (
              <Clock className="w-3 h-3 text-gray-400" />
            ) : msg.deliveryStatus === 'delivered' ? (
              <CheckCheck className="w-3.5 h-3.5 text-blue-500" />
            ) : msg.deliveryStatus === 'failed' ? (
              <AlertCircle className="w-3 h-3 text-red-500" />
            ) : (
              <Check className="w-3 h-3 text-gray-400" />
            )
          )}
        </div>
        {/* E2EE failure warning */}
        {msg.e2eeFailed && (
          <div className={`flex items-center gap-1 mt-1 text-xs text-amber-600 dark:text-amber-400 ${isSent ? 'justify-end' : 'justify-start'}`}>
            <AlertTriangle className="w-3 h-3" />
            <span title={msg.e2eeFailureReason}>Not encrypted</span>
          </div>
        )}
        {!isSent && readReceipt && readReceipt.readBy !== 'web' && (
          <p className="text-xs text-gray-500 dark:text-gray-400 mt-1 text-right">
            Read on {readReceipt.readDeviceName || readReceipt.readBy}
            {readTime ? ` at ${readTime}` : ''}
          </p>
        )}
      </div>
    </div>
  )
})

export default function MessageView({ onOpenAI }: MessageViewProps) {
  // Use individual selectors for better performance (prevents re-renders from unrelated store changes)
  const messages = useAppStore((state) => state.messages)
  const selectedConversation = useAppStore((state) => state.selectedConversation)
  const userId = useAppStore((state) => state.userId)
  const readReceipts = useAppStore((state) => state.readReceipts)
  const spamMessages = useAppStore((state) => state.spamMessages)
  const selectedSpamAddress = useAppStore((state) => state.selectedSpamAddress)
  const activeFolder = useAppStore((state) => state.activeFolder)
  const [newMessage, setNewMessage] = useState('')
  const [isSending, setIsSending] = useState(false)
  const [sendError, setSendError] = useState<string | null>(null)
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [showSendTip, setShowSendTip] = useState(() => {
    if (typeof window !== 'undefined') {
      return localStorage.getItem('syncflow_hide_send_tip') !== 'true'
    }
    return true
  })
  const [sendAddressOverride, setSendAddressOverride] = useState<string | null>(null)
  const [selectedImages, setSelectedImages] = useState<SelectedImage[]>([])
  const [isUploading, setIsUploading] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const dismissSendTip = useCallback(() => {
    setShowSendTip(false)
    localStorage.setItem('syncflow_hide_send_tip', 'true')
  }, [])

  const setPreferredSendAddress = useCallback((address: string | null) => {
    setSendAddressOverride(address)
    if (typeof window === 'undefined' || !selectedConversation) return
    const key = `preferred_send_address_${selectedConversation}`
    if (!address) {
      localStorage.removeItem(key)
    } else {
      localStorage.setItem(key, address)
    }
  }, [selectedConversation])

  // Check authentication status
  useEffect(() => {
    const checkAuth = async () => {
      // Check VPS mode - must match firebase.ts isVPSMode() logic
      const isVPSMode = localStorage.getItem('useVPSMode') === 'true' ||
                        !!process.env.NEXT_PUBLIC_VPS_URL ||
                        !!localStorage.getItem('vps_access_token') ||
                        vpsService.isAuthenticated ||
                        true // Default to VPS mode

      if (isVPSMode) {
        // VPS mode: check VPS auth or stored user ID
        const hasAuth = vpsService.isAuthenticated || !!localStorage.getItem('syncflow_user_id')
        setIsAuthenticated(hasAuth)
        return
      }

      // Firebase mode (legacy)
      const authUser = await waitForAuth()
      setIsAuthenticated(!!authUser)
    }
    checkAuth()
  }, [])

  // Filter messages for selected conversation (using normalized address)
  const conversationMessages = useMemo(() => {
    if (!selectedConversation) return []

    // selectedConversation is now a normalized address
    return messages
      .filter((msg) => normalizePhoneNumber(msg.address) === selectedConversation)
      .sort((a, b) => a.date - b.date)
  }, [messages, selectedConversation])

  // Get the original address for sending (prefer the most recent one)
  const sendAddress = useMemo(() => {
    if (conversationMessages.length === 0) return selectedConversation || ''
    // Use the address from the most recent message
    const mostRecent = [...conversationMessages].sort((a, b) => b.date - a.date)[0]
    return mostRecent?.address || selectedConversation || ''
  }, [conversationMessages, selectedConversation])

  const allSendAddresses = useMemo(() => {
    const unique = Array.from(new Set(conversationMessages.map((msg) => msg.address)))
    return unique.length > 0 ? unique : [sendAddress]
  }, [conversationMessages, sendAddress])

  const effectiveSendAddress = sendAddressOverride || sendAddress

  useEffect(() => {
    if (typeof window === 'undefined' || !selectedConversation) return
    const key = `preferred_send_address_${selectedConversation}`
    const stored = localStorage.getItem(key)
    if (stored && allSendAddresses.includes(stored)) {
      setSendAddressOverride(stored)
    } else {
      setSendAddressOverride(null)
    }
  }, [selectedConversation, allSendAddresses])

  // Auto-scroll to bottom when messages change
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [conversationMessages])

  // Mark messages as read when opening a conversation
  useEffect(() => {
    if (!userId || !selectedConversation || activeFolder !== 'inbox') return
    if (conversationMessages.length === 0) return

    const unreadIds = conversationMessages
      .filter((msg) => msg.type === 1 && !readReceipts[msg.id])
      .map((msg) => msg.id)

    if (unreadIds.length === 0) return

    // Optimistically update local readReceipts so unread badges disappear immediately
    const now = Date.now()
    const updatedReceipts = { ...readReceipts }
    for (const id of unreadIds) {
      updatedReceipts[id] = {
        messageId: id,
        readAt: now,
        readBy: 'web',
        conversationAddress: selectedConversation,
      }
    }
    useAppStore.getState().setReadReceipts(updatedReceipts)

    // Use VPS service if authenticated, otherwise fall back to Firebase
    if (vpsService.isAuthenticated) {
      Promise.all(unreadIds.map((id) => vpsService.markMessageRead(id))).catch((error) => {
        console.error('Failed to mark messages read:', error)
      })
    } else {
      markMessagesRead(userId, unreadIds, selectedConversation).catch((error) => {
        console.error('Failed to mark messages read:', error)
      })
    }
  }, [userId, selectedConversation, conversationMessages, readReceipts, activeFolder])

  const handleImageSelect = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files
    if (!files) return

    const newImages: SelectedImage[] = []
    for (let i = 0; i < files.length && selectedImages.length + newImages.length < 5; i++) {
      const file = files[i]
      if (file.type.startsWith('image/')) {
        newImages.push({
          file,
          preview: URL.createObjectURL(file),
        })
      }
    }

    setSelectedImages((prev) => [...prev, ...newImages])
    // Reset input so same file can be selected again
    e.target.value = ''
  }, [selectedImages.length])

  const removeImage = useCallback((index: number) => {
    setSelectedImages((prev) => {
      const newImages = [...prev]
      URL.revokeObjectURL(newImages[index].preview)
      newImages.splice(index, 1)
      return newImages
    })
  }, [])

  const handleSend = useCallback(async () => {
    const hasContent = newMessage.trim() || selectedImages.length > 0
    if (!hasContent || !userId || !effectiveSendAddress || isSending) return

    // Check authentication first
    if (!isAuthenticated) {
      setSendError('Not authenticated. Please refresh and try again.')
      return
    }

    setIsSending(true)
    setSendError(null)

    try {
      if (vpsService.isAuthenticated) {
        // VPS mode: use VPS service to send
        if (selectedImages.length > 0) {
          setIsUploading(true)
          const attachments = await Promise.all(
            selectedImages.map(async (img) => {
              const { uploadUrl, fileKey } = await vpsService.getFileUploadUrl(
                img.file.name,
                img.file.type,
                img.file.size
              )
              await fetch(uploadUrl, {
                method: 'PUT',
                headers: { 'Content-Type': img.file.type },
                body: img.file,
              })
              await vpsService.confirmFileUpload(fileKey, img.file.size)
              return { fileKey, contentType: img.file.type, fileName: img.file.name }
            })
          )
          setIsUploading(false)
          await vpsService.sendMmsMessage(effectiveSendAddress, newMessage.trim(), attachments)
          selectedImages.forEach((img) => URL.revokeObjectURL(img.preview))
          setSelectedImages([])
        } else {
          await vpsService.sendMessage(effectiveSendAddress, newMessage.trim())
        }
        // Optimistic update: show sent message immediately in conversation
        const store = useAppStore.getState()
        store.setMessages(
          [...store.messages, {
            id: `optimistic_${Date.now()}`,
            address: effectiveSendAddress,
            body: newMessage.trim(),
            date: Date.now(),
            type: 2,
            read: true,
            isMms: selectedImages.length > 0,
            attachments: [],
          }].sort((a, b) => b.date - a.date)
        )
      } else if (selectedImages.length > 0) {
        // Firebase mode: Upload images and send as MMS
        setIsUploading(true)
        const uploadedAttachments = await Promise.all(
          selectedImages.map((img) => uploadMmsImage(userId, img.file))
        )
        setIsUploading(false)

        await sendMmsFromWeb(userId, effectiveSendAddress, newMessage.trim(), uploadedAttachments)

        // Clean up preview URLs
        selectedImages.forEach((img) => URL.revokeObjectURL(img.preview))
        setSelectedImages([])
      } else {
        // Firebase mode: Send as regular SMS
        await sendSmsFromWeb(userId, effectiveSendAddress, newMessage.trim())
      }
      setNewMessage('')
    } catch (error: any) {
      console.error('Error sending message:', error)
      if (error?.code === 'PERMISSION_DENIED') {
        setSendError('Permission denied. Please re-pair your device.')
      } else {
        setSendError(error?.message || 'Failed to send message. Please try again.')
      }
    } finally {
      setIsSending(false)
      setIsUploading(false)
    }
  }, [newMessage, selectedImages, userId, effectiveSendAddress, isSending, isAuthenticated])

  const handleKeyPress = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }, [handleSend])

  if (activeFolder === 'spam') {
    const selected = selectedSpamAddress
    const spamList = selected
      ? spamMessages.filter((msg) => msg.address === selected).sort((a, b) => a.date - b.date)
      : []

    return (
      <div className="flex-1 flex flex-col min-h-0 overflow-hidden bg-gray-50 dark:bg-gray-900">
        <div className="flex-shrink-0 bg-white dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700 px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-full bg-red-100 dark:bg-red-900/40 flex items-center justify-center text-red-600 font-semibold">
                {selected ? selected.charAt(0).toUpperCase() : 'S'}
              </div>
              <div>
                <h2 className="font-semibold text-gray-900 dark:text-white">Spam</h2>
                <p className="text-sm text-gray-500 dark:text-gray-400">
                  {selected ? `From: ${selected}` : 'Select a spam sender'}
                </p>
              </div>
            </div>
          </div>
        </div>

        <div className="flex-1 min-h-0 h-0 overflow-y-auto p-6 space-y-4">
          {selected ? (
            spamList.length === 0 ? (
              <div className="text-center text-gray-500 dark:text-gray-400">
                No spam messages for this sender
              </div>
            ) : (
              spamList.map((msg) => (
                <div key={msg.id} className="flex justify-start">
                  <div className="max-w-md">
                    <div className="rounded-2xl bg-red-50 dark:bg-red-900/20 text-gray-900 dark:text-white px-4 py-2">
                      <p className="whitespace-pre-wrap break-words">{msg.body}</p>
                    </div>
                    <div className="text-xs text-gray-500 dark:text-gray-400 mt-1">
                      {format(new Date(msg.date), 'MMM d, h:mm a')}
                    </div>
                  </div>
                </div>
              ))
            )
          ) : (
            <div className="text-center text-gray-500 dark:text-gray-400">
              Select a spam sender from the sidebar
            </div>
          )}
        </div>
      </div>
    )
  }

  if (!selectedConversation) {
    return (
      <div className="flex-1 flex items-center justify-center min-h-0 overflow-hidden bg-gray-50 dark:bg-gray-900">
        <div className="text-center">
          <MessageSquare className="w-20 h-20 text-gray-300 dark:text-gray-600 mx-auto mb-4" />
          <h3 className="text-xl font-semibold text-gray-600 dark:text-gray-400 mb-2">
            No conversation selected
          </h3>
          <p className="text-gray-500 dark:text-gray-500">
            Choose a conversation from the left to start messaging
          </p>
        </div>
      </div>
    )
  }

  const contact = conversationMessages[0]?.contactName || sendAddress

  return (
    <div className="flex-1 flex flex-col min-h-0 overflow-hidden bg-gray-50 dark:bg-gray-900">
      {/* Header */}
      <div className="flex-shrink-0 bg-white dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700 px-6 py-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            {/* Avatar */}
            <div className="w-10 h-10 rounded-full bg-gradient-to-br from-blue-400 to-blue-600 flex items-center justify-center text-white font-semibold">
              {contact.charAt(0).toUpperCase()}
            </div>

            {/* Name and Status */}
            <div>
              <h2 className="font-semibold text-gray-900 dark:text-white">{contact}</h2>
              <p className="text-sm text-gray-500 dark:text-gray-400">
                Send to: {effectiveSendAddress}
              </p>
              {allSendAddresses.length > 1 && (
                <div className="mt-1">
                  <label className="text-xs text-gray-500 dark:text-gray-400 mr-2">
                    Send using
                  </label>
                  <select
                    value={sendAddressOverride ?? ''}
                    onChange={(e) => {
                      const value = e.target.value
                      setPreferredSendAddress(value ? value : null)
                    }}
                    className="text-xs border border-gray-300 dark:border-gray-600 rounded px-2 py-1 bg-white dark:bg-gray-700 text-gray-700 dark:text-gray-200"
                  >
                    <option value="">Auto (most recent)</option>
                    {allSendAddresses.map((addr) => (
                      <option key={addr} value={addr}>
                        {addr}
                      </option>
                    ))}
                  </select>
                </div>
              )}
            </div>
          </div>

          {/* Actions */}
          <div className="flex items-center gap-2">
            <button
              className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-600 dark:text-gray-400"
              title="More"
            >
              <MoreVertical className="w-5 h-5" />
            </button>
          </div>
        </div>
      </div>

      {/* Messages */}
      <div className="flex-1 min-h-0 h-0 overflow-y-auto p-6 space-y-4">
        {conversationMessages.map((msg) => (
          <MessageBubble
            key={msg.id}
            msg={msg}
            isSent={msg.type === 2}
            readReceipt={readReceipts[msg.id]}
          />
        ))}
        <div ref={messagesEndRef} />
      </div>

      {/* Error message */}
      {sendError && (
        <div className="flex-shrink-0 px-6 py-2 bg-red-50 dark:bg-red-900/20 border-t border-red-200 dark:border-red-800">
          <p className="text-sm text-red-600 dark:text-red-400">{sendError}</p>
        </div>
      )}

      {/* Send tip banner */}
      {showSendTip && (
        <div className="flex-shrink-0 px-4 py-2 bg-blue-50 dark:bg-blue-900/20 border-t border-blue-200 dark:border-blue-800 flex items-center gap-3">
          <Info className="w-4 h-4 text-blue-600 dark:text-blue-400 flex-shrink-0" />
          <p className="text-xs text-blue-700 dark:text-blue-300 flex-1">
            <span className="font-medium">To send SMS:</span> Enable &quot;Background Sync&quot; in the Android app under Desktop Integration.
          </p>
          <button
            onClick={dismissSendTip}
            className="p-1 hover:bg-blue-100 dark:hover:bg-blue-800 rounded transition-colors"
            title="Dismiss"
          >
            <X className="w-3 h-3 text-blue-600 dark:text-blue-400" />
          </button>
        </div>
      )}

      {/* Input */}
      <div className="flex-shrink-0 bg-white dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700 px-6 py-4">
        {/* Image Preview */}
        {selectedImages.length > 0 && (
          <div className="flex gap-2 mb-3 overflow-x-auto pb-2">
            {selectedImages.map((img, idx) => (
              <div key={idx} className="relative flex-shrink-0">
                <img
                  src={img.preview}
                  alt={`Selected ${idx + 1}`}
                  className="h-20 w-20 object-cover rounded-lg"
                />
                <button
                  onClick={() => removeImage(idx)}
                  className="absolute -top-2 -right-2 w-6 h-6 bg-red-500 rounded-full flex items-center justify-center text-white hover:bg-red-600 transition-colors"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            ))}
          </div>
        )}

        <div className="flex items-end gap-3">
          {/* Hidden file input */}
          <input
            type="file"
            ref={fileInputRef}
            onChange={handleImageSelect}
            accept="image/*"
            multiple
            className="hidden"
          />

          {/* Attach Image Button */}
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={isSending || selectedImages.length >= 5}
            className="p-3 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-600 dark:text-gray-400 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            title="Attach image (max 5)"
          >
            <Paperclip className="w-5 h-5" />
          </button>

          <textarea
            value={newMessage}
            onChange={(e) => {
              setNewMessage(e.target.value)
              setSendError(null)
            }}
            onKeyPress={handleKeyPress}
            placeholder={selectedImages.length > 0 ? "Add a caption (optional)..." : "Type a message..."}
            rows={1}
            className="flex-1 resize-none px-4 py-3 border border-gray-300 dark:border-gray-600 rounded-lg bg-gray-50 dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent max-h-32"
          />

          {/* AI Assistant Button */}
          {onOpenAI && (
            <button
              onClick={onOpenAI}
              className="p-3 rounded-lg bg-gradient-to-br from-purple-500 to-blue-600 hover:from-purple-600 hover:to-blue-700 text-white transition-all"
              title="AI Assistant"
            >
              <Brain className="w-5 h-5" />
            </button>
          )}

          {/* Send Button */}
          <button
            onClick={handleSend}
            disabled={(!newMessage.trim() && selectedImages.length === 0) || isSending || !isAuthenticated}
            className="p-3 rounded-lg bg-blue-600 hover:bg-blue-700 disabled:bg-gray-400 disabled:cursor-not-allowed text-white transition-colors flex items-center justify-center min-w-[48px]"
            title={!isAuthenticated ? 'Not authenticated' : selectedImages.length > 0 ? 'Send MMS' : 'Send message'}
          >
            {isSending ? (
              <Loader2 className="w-5 h-5 animate-spin" />
            ) : (
              <Send className="w-5 h-5" />
            )}
          </button>
        </div>
        <p className="text-xs text-gray-500 dark:text-gray-400 mt-2">
          {isUploading ? (
            <span className="text-blue-500">Uploading images...</span>
          ) : selectedImages.length > 0 ? (
            <span>{selectedImages.length} image(s) attached - will send as MMS</span>
          ) : (
            'Press Enter to send, Shift+Enter for new line'
          )}
        </p>
      </div>
    </div>
  )
}
