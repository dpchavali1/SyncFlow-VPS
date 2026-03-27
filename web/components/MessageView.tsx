'use client'

import { useState, useEffect, useRef, useMemo, useCallback, memo } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { Send, MoreVertical, MessageSquare, Brain, Loader2, Info, X, Image as ImageIcon, Paperclip, AlertTriangle, Check, CheckCheck, Clock, AlertCircle, ChevronDown, ShieldAlert } from 'lucide-react'
import { useAppStore } from '@/lib/store'
import { markMessagesRead, sendSmsFromWeb, sendMmsFromWeb, uploadMmsImage, waitForAuth } from '@/lib/firebase'
import vpsService from '@/lib/vps'
import { normalizePhoneForConversation } from '@/lib/phoneNumberNormalizer'
import { format } from 'date-fns'
import { messageBubbleIn, fadeIn, floatingAnimation } from '@/lib/animations'

interface MessageViewProps {
  onOpenAI?: () => void
}

interface SelectedImage {
  file: File
  preview: string
}

interface MessageBubbleProps {
  msg: any
  isSent: boolean
  readReceipt: any
  isNew?: boolean
}

// Generate consistent avatar gradient from name/address
function getAvatarGradient(name: string): string {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash)
  }
  const gradients = [
    'from-blue-400 to-blue-600',
    'from-violet-400 to-purple-600',
    'from-cyan-400 to-blue-500',
    'from-indigo-400 to-violet-600',
    'from-blue-500 to-indigo-600',
    'from-sky-400 to-blue-600',
    'from-purple-400 to-indigo-600',
    'from-teal-400 to-cyan-600',
  ]
  return gradients[Math.abs(hash) % gradients.length]
}

/**
 * Memoized message bubble component to prevent unnecessary re-renders.
 *
 * Renders a single SMS/MMS message with:
 *   - iMessage-style alignment (sent = right/blue gradient, received = left/glass)
 *   - MMS image attachments with click-to-open and graceful error placeholders
 *   - Delivery status indicators: clock=sending, check=sent, double-check=delivered, !=failed
 *   - E2EE failure warnings when decryption of an encrypted message fails
 *   - Read receipts from other devices (e.g. "Read on Mac at 3:45 PM")
 */
/** MMS image with React state-based error handling (no DOM manipulation). */
function MmsImage({ src, isSent }: { src: string; isSent: boolean }) {
  const [errored, setErrored] = useState(false)

  if (errored) {
    return (
      <div className={`p-4 flex items-center gap-2 ${isSent ? 'text-white/60' : 'text-gray-400 dark:text-gray-500'}`}>
        <ImageIcon className="w-5 h-5" />
        <span className="text-sm">Image failed to load</span>
      </div>
    )
  }

  return (
    <a href={src} target="_blank" rel="noopener noreferrer" className="block">
      <img
        src={src}
        alt="MMS attachment"
        className="max-w-full h-auto cursor-pointer rounded-xl hover:opacity-90 transition-opacity"
        style={{ maxHeight: '300px', objectFit: 'contain' }}
        onError={() => setErrored(true)}
      />
    </a>
  )
}

const MessageBubble = memo(function MessageBubble({ msg, isSent, readReceipt, isNew }: MessageBubbleProps) {
  const timestamp = format(new Date(msg.date), 'MMM d, h:mm a')
  const readTime =
    readReceipt?.readAt && typeof readReceipt.readAt === 'number'
      ? format(new Date(readReceipt.readAt), 'h:mm a')
      : null
  const imageAttachments = msg.attachments?.filter((att: any) =>
    att.contentType?.startsWith('image/') || att.type === 'image'
  ) || []
  const hasImages = imageAttachments.length > 0

  const bubbleContent = (
    <div className={`flex ${isSent ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-md ${isSent ? 'order-2' : 'order-1'}`}>
        <div
          className={`rounded-2xl overflow-hidden shadow-sm ${
            isSent
              ? `${hasImages && !msg.body ? '' : 'bubble-sent'} rounded-br-md`
              : 'bubble-received rounded-bl-md'
          }`}
        >
          {/* MMS image attachments */}
          {imageAttachments.map((att: any, idx: number) => {
            const imageSrc = att.url

            if (!imageSrc) {
              return (
                <div key={idx} className={`p-4 flex items-center gap-2 ${isSent ? 'text-white/60' : 'text-gray-400 dark:text-gray-500'}`}>
                  <ImageIcon className="w-5 h-5" />
                  <span className="text-sm">Image not available</span>
                </div>
              )
            }

            return <MmsImage key={idx} src={imageSrc} isSent={isSent} />
          })}

          {/* Text content */}
          {msg.body && (
            <p className={`whitespace-pre-wrap break-words text-[15px] leading-relaxed ${hasImages ? 'p-3' : 'px-4 py-2.5'}`}>
              {msg.body}
            </p>
          )}

          {/* Show MMS indicator if no body and no displayable images */}
          {!msg.body && msg.isMms && !hasImages && (
            <div className={`px-4 py-2.5 flex items-center gap-2 ${isSent ? 'text-white/60' : 'text-gray-400 dark:text-gray-500'}`}>
              <ImageIcon className="w-4 h-4" />
              <span className="text-sm italic">MMS message</span>
            </div>
          )}
        </div>

        {/* Timestamp + delivery status */}
        <div
          className={`flex items-center gap-1.5 text-[11px] text-gray-400 dark:text-gray-500 mt-1 px-1 ${
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
          <div className={`flex items-center gap-1 mt-1 px-1 text-[11px] text-amber-600 dark:text-amber-400 ${isSent ? 'justify-end' : 'justify-start'}`}>
            <AlertTriangle className="w-3 h-3" />
            <span title={msg.e2eeFailureReason}>Not encrypted</span>
          </div>
        )}

        {/* Read receipt from other devices */}
        {!isSent && readReceipt && readReceipt.readBy !== 'web' && (
          <p className="text-[11px] text-gray-400 dark:text-gray-500 mt-1 px-1 text-right">
            Read on {readReceipt.readDeviceName || readReceipt.readBy}
            {readTime ? ` at ${readTime}` : ''}
          </p>
        )}
      </div>
    </div>
  )

  // Only animate new messages with spring physics
  if (isNew) {
    return (
      <motion.div
        variants={messageBubbleIn}
        initial="hidden"
        animate="visible"
      >
        {bubbleContent}
      </motion.div>
    )
  }

  return bubbleContent
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
  const [showScrollButton, setShowScrollButton] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const scrollContainerRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  // Track seen message IDs so we only animate truly new ones
  const seenMessageIds = useRef<Set<string>>(new Set())

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
      // VPS mode is always enabled (Firebase legacy path removed)
      const isVPSMode = true

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

  // Filter messages for the selected conversation by normalizing phone numbers
  // to their last 10 digits, handling format differences (+1, parentheses, dashes, etc.)
  const conversationMessages = useMemo(() => {
    if (!selectedConversation) return []

    // selectedConversation is now a normalized address
    return messages
      .filter((msg) => normalizePhoneForConversation(msg.address) === selectedConversation)
      .sort((a, b) => a.date - b.date)
  }, [messages, selectedConversation])

  // Prefer the most recent raw address for sending, since the conversation key
  // is normalized (last 10 digits) but we need the full E.164 number for the API
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

  // Track scroll position for scroll-to-bottom button
  useEffect(() => {
    const container = scrollContainerRef.current
    if (!container) return

    const handleScroll = () => {
      const { scrollHeight, scrollTop, clientHeight } = container
      const distanceFromBottom = scrollHeight - scrollTop - clientHeight
      setShowScrollButton(distanceFromBottom > 200)
    }

    container.addEventListener('scroll', handleScroll, { passive: true })
    return () => container.removeEventListener('scroll', handleScroll)
  }, [])

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [])

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

  const setSpamMessages = useAppStore((state) => state.setSpamMessages)
  const setSelectedSpamAddress = useAppStore((state) => state.setSelectedSpamAddress)

  const handleNotSpam = useCallback(async () => {
    if (!selectedSpamAddress) return
    try {
      await vpsService.addToWhitelist(selectedSpamAddress)
      // Remove all messages from this sender locally
      const remaining = useAppStore.getState().spamMessages.filter(
        (msg) => msg.address !== selectedSpamAddress
      )
      setSpamMessages(remaining)
      // Select next sender if available
      const nextAddress = remaining.length > 0 ? remaining[0].address : null
      setSelectedSpamAddress(nextAddress)
    } catch (err) {
      console.error('Failed to whitelist sender:', err)
    }
  }, [selectedSpamAddress, setSpamMessages, setSelectedSpamAddress])

  const handleDeleteSpamSender = useCallback(async () => {
    if (!selectedSpamAddress) return
    try {
      const toDelete = useAppStore.getState().spamMessages.filter(
        (msg) => msg.address === selectedSpamAddress
      )
      await Promise.all(toDelete.map((msg) => vpsService.deleteSpamMessage(msg.id)))
      const remaining = useAppStore.getState().spamMessages.filter(
        (msg) => msg.address !== selectedSpamAddress
      )
      setSpamMessages(remaining)
      const nextAddress = remaining.length > 0 ? remaining[0].address : null
      setSelectedSpamAddress(nextAddress)
    } catch (err) {
      console.error('Failed to delete spam messages:', err)
    }
  }, [selectedSpamAddress, setSpamMessages, setSelectedSpamAddress])

  // ─── SPAM FOLDER VIEW ───
  if (activeFolder === 'spam') {
    const selected = selectedSpamAddress
    const spamList = selected
      ? spamMessages.filter((msg) => msg.address === selected).sort((a, b) => a.date - b.date)
      : []

    return (
      <div className="flex-1 flex flex-col min-h-0 overflow-hidden bg-mesh">
        {/* Spam header */}
        <div className="flex-shrink-0 glass-panel mx-3 mt-3 rounded-2xl px-5 py-3.5">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-red-400 to-red-600 flex items-center justify-center text-white font-semibold shadow-md">
                {selected ? (
                  <span className="text-sm">{selected.charAt(0).toUpperCase()}</span>
                ) : (
                  <ShieldAlert className="w-5 h-5" />
                )}
              </div>
              <div>
                <h2 className="font-semibold text-gray-900 dark:text-white">Spam</h2>
                <p className="text-sm text-gray-500 dark:text-gray-400">
                  {selected ? `From: ${selected}` : 'Select a spam sender'}
                </p>
              </div>
            </div>
            {selected && (
              <div className="flex items-center gap-2">
                <motion.button
                  whileHover={{ scale: 1.02 }}
                  whileTap={{ scale: 0.98 }}
                  onClick={handleNotSpam}
                  className="px-4 py-2 text-sm font-medium rounded-xl bg-emerald-500/10 text-emerald-600 hover:bg-emerald-500/20 dark:text-emerald-400 transition-colors"
                >
                  Not Spam
                </motion.button>
                <motion.button
                  whileHover={{ scale: 1.02 }}
                  whileTap={{ scale: 0.98 }}
                  onClick={handleDeleteSpamSender}
                  className="px-4 py-2 text-sm font-medium rounded-xl bg-red-500/10 text-red-600 hover:bg-red-500/20 dark:text-red-400 transition-colors"
                >
                  Delete
                </motion.button>
              </div>
            )}
          </div>
        </div>

        {/* Spam messages */}
        <div className="flex-1 min-h-0 h-0 overflow-y-auto p-6 space-y-3">
          {selected ? (
            spamList.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-full text-gray-400 dark:text-gray-500">
                <ShieldAlert className="w-12 h-12 mb-3 opacity-40" />
                <p className="text-sm">No spam messages for this sender</p>
              </div>
            ) : (
              spamList.map((msg) => (
                <div key={msg.id} className="flex justify-start">
                  <div className="max-w-md">
                    <div className="rounded-2xl rounded-bl-md bg-red-50/80 dark:bg-red-900/20 text-gray-900 dark:text-white px-4 py-2.5 shadow-sm">
                      <p className="whitespace-pre-wrap break-words text-[15px] leading-relaxed">{msg.body}</p>
                    </div>
                    <div className="text-[11px] text-gray-400 dark:text-gray-500 mt-1 px-1">
                      {format(new Date(msg.date), 'MMM d, h:mm a')}
                    </div>
                  </div>
                </div>
              ))
            )
          ) : (
            <div className="flex flex-col items-center justify-center h-full text-gray-400 dark:text-gray-500">
              <motion.div {...floatingAnimation}>
                <ShieldAlert className="w-16 h-16 mb-4 opacity-30" />
              </motion.div>
              <p className="text-sm">Select a spam sender from the sidebar</p>
            </div>
          )}
        </div>
      </div>
    )
  }

  // ─── EMPTY STATE (no conversation selected) ───
  if (!selectedConversation) {
    return (
      <div className="flex-1 flex items-center justify-center min-h-0 overflow-hidden bg-mesh">
        <div className="text-center">
          <motion.div {...floatingAnimation}>
            <div className="w-20 h-20 rounded-3xl bg-gradient-to-br from-blue-500/10 to-violet-500/10 dark:from-blue-500/20 dark:to-violet-500/20 flex items-center justify-center mx-auto mb-6">
              <MessageSquare className="w-10 h-10 text-blue-400/60 dark:text-blue-400/40" />
            </div>
          </motion.div>
          <h3 className="text-xl font-semibold text-gray-600 dark:text-gray-400 mb-2">
            No conversation selected
          </h3>
          <p className="text-sm text-gray-400 dark:text-gray-500 max-w-[240px] mx-auto">
            Choose a conversation from the left to start messaging
          </p>
        </div>
      </div>
    )
  }

  // ─── MAIN MESSAGE VIEW ───
  const contact = conversationMessages[0]?.contactName || sendAddress
  const avatarGradient = getAvatarGradient(contact)

  // Determine which messages are "new" (unseen) for animation
  const newMessageIds = new Set<string>()
  conversationMessages.forEach((msg) => {
    if (!seenMessageIds.current.has(msg.id)) {
      newMessageIds.add(msg.id)
      seenMessageIds.current.add(msg.id)
    }
  })

  return (
    <div className="flex-1 flex flex-col min-h-0 overflow-hidden bg-mesh">
      {/* Header */}
      <div className="flex-shrink-0 glass-panel mx-3 mt-3 rounded-2xl px-5 py-3.5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            {/* Avatar with gradient ring */}
            <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${avatarGradient} flex items-center justify-center text-white font-semibold shadow-md text-sm`}>
              {contact.charAt(0).toUpperCase()}
            </div>

            {/* Name and address */}
            <div>
              <h2 className="font-semibold text-gray-900 dark:text-white">{contact}</h2>
              <div className="flex items-center gap-2">
                <span className="text-xs text-gray-400 dark:text-gray-500 bg-gray-100 dark:bg-gray-800 px-2 py-0.5 rounded-full">
                  {effectiveSendAddress}
                </span>
              </div>
              {allSendAddresses.length > 1 && (
                <div className="mt-1.5">
                  <label className="text-[11px] text-gray-400 dark:text-gray-500 mr-2">
                    Send using
                  </label>
                  <select
                    value={sendAddressOverride ?? ''}
                    onChange={(e) => {
                      const value = e.target.value
                      setPreferredSendAddress(value ? value : null)
                    }}
                    className="text-[11px] glass-input rounded-lg px-2 py-1 text-gray-700 dark:text-gray-200"
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
          <div className="flex items-center gap-1">
            <button
              className="p-2 rounded-xl hover:bg-gray-100 dark:hover:bg-white/5 text-gray-400 dark:text-gray-500 transition-colors"
              title="More"
            >
              <MoreVertical className="w-5 h-5" />
            </button>
          </div>
        </div>
      </div>

      {/* Messages */}
      <div
        ref={scrollContainerRef}
        className="flex-1 min-h-0 h-0 overflow-y-auto px-6 py-4 space-y-3"
      >
        {conversationMessages.map((msg) => (
          <MessageBubble
            key={msg.id}
            msg={msg}
            isSent={msg.type === 2}
            readReceipt={readReceipts[msg.id]}
            isNew={newMessageIds.has(msg.id)}
          />
        ))}
        <div ref={messagesEndRef} />
      </div>

      {/* Scroll to bottom button */}
      <AnimatePresence>
        {showScrollButton && (
          <motion.button
            variants={fadeIn}
            initial="hidden"
            animate="visible"
            exit="exit"
            onClick={scrollToBottom}
            className="absolute bottom-32 right-8 glass-elevated rounded-full p-2.5 text-gray-500 dark:text-gray-400 hover:text-blue-500 transition-colors shadow-lg z-10"
          >
            <ChevronDown className="w-5 h-5" />
          </motion.button>
        )}
      </AnimatePresence>

      {/* Error message */}
      <AnimatePresence>
        {sendError && (
          <motion.div
            variants={fadeIn}
            initial="hidden"
            animate="visible"
            exit="exit"
            className="flex-shrink-0 mx-3 mb-2 px-4 py-2.5 rounded-xl bg-red-50/80 dark:bg-red-900/20 border border-red-200/50 dark:border-red-800/30"
          >
            <p className="text-sm text-red-600 dark:text-red-400">{sendError}</p>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Send tip banner */}
      <AnimatePresence>
        {showSendTip && (
          <motion.div
            variants={fadeIn}
            initial="hidden"
            animate="visible"
            exit="exit"
            className="flex-shrink-0 mx-3 mb-2 px-4 py-2.5 rounded-xl bg-blue-50/80 dark:bg-blue-900/20 border border-blue-200/50 dark:border-blue-800/30 flex items-center gap-3"
          >
            <Info className="w-4 h-4 text-blue-500 flex-shrink-0" />
            <p className="text-xs text-blue-600 dark:text-blue-300 flex-1">
              <span className="font-medium">To send SMS:</span> Enable &quot;Background Sync&quot; in the Android app under Desktop Integration.
            </p>
            <button
              onClick={dismissSendTip}
              className="p-1 hover:bg-blue-100 dark:hover:bg-blue-800/50 rounded-lg transition-colors"
              title="Dismiss"
            >
              <X className="w-3 h-3 text-blue-500" />
            </button>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Input area */}
      <div className="flex-shrink-0 glass-panel mx-3 mb-3 rounded-2xl px-4 py-3">
        {/* Image Preview */}
        <AnimatePresence>
          {selectedImages.length > 0 && (
            <motion.div
              variants={fadeIn}
              initial="hidden"
              animate="visible"
              exit="exit"
              className="flex gap-2 mb-3 overflow-x-auto pb-2"
            >
              {selectedImages.map((img, idx) => (
                <div key={idx} className="relative flex-shrink-0">
                  <img
                    src={img.preview}
                    alt={`Selected ${idx + 1}`}
                    className="h-20 w-20 object-cover rounded-xl shadow-sm"
                  />
                  <button
                    onClick={() => removeImage(idx)}
                    className="absolute -top-1.5 -right-1.5 w-5 h-5 bg-red-500 rounded-full flex items-center justify-center text-white hover:bg-red-600 transition-colors shadow-sm"
                  >
                    <X className="w-3 h-3" />
                  </button>
                </div>
              ))}
            </motion.div>
          )}
        </AnimatePresence>

        <div className="flex items-end gap-2">
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
          <motion.button
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
            onClick={() => fileInputRef.current?.click()}
            disabled={isSending || selectedImages.length >= 5}
            className="p-2.5 rounded-xl hover:bg-gray-100 dark:hover:bg-white/5 text-gray-400 dark:text-gray-500 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            title="Attach image (max 5)"
          >
            <Paperclip className="w-5 h-5" />
          </motion.button>

          <textarea
            value={newMessage}
            onChange={(e) => {
              setNewMessage(e.target.value)
              setSendError(null)
            }}
            onKeyPress={handleKeyPress}
            placeholder={selectedImages.length > 0 ? "Add a caption (optional)..." : "Type a message..."}
            rows={1}
            className="flex-1 resize-none px-4 py-2.5 glass-input rounded-xl text-gray-900 dark:text-white text-[15px] max-h-32 placeholder:text-gray-400 dark:placeholder:text-gray-500 focus:outline-none"
          />

          {/* AI Assistant Button */}
          {onOpenAI && (
            <motion.button
              whileHover={{ scale: 1.05 }}
              whileTap={{ scale: 0.95 }}
              onClick={onOpenAI}
              className="p-2.5 rounded-xl bg-gradient-to-br from-violet-500 to-blue-600 hover:from-violet-600 hover:to-blue-700 text-white shadow-md shadow-violet-500/20 transition-all"
              title="AI Assistant"
            >
              <Brain className="w-5 h-5" />
            </motion.button>
          )}

          {/* Send Button */}
          <motion.button
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.95 }}
            onClick={handleSend}
            disabled={(!newMessage.trim() && selectedImages.length === 0) || isSending || !isAuthenticated}
            className="p-2.5 rounded-xl bg-gradient-to-br from-blue-500 to-blue-600 hover:from-blue-600 hover:to-blue-700 disabled:from-gray-300 disabled:to-gray-400 dark:disabled:from-gray-600 dark:disabled:to-gray-700 disabled:cursor-not-allowed text-white shadow-md shadow-blue-500/20 disabled:shadow-none transition-all flex items-center justify-center min-w-[44px]"
            title={!isAuthenticated ? 'Not authenticated' : selectedImages.length > 0 ? 'Send MMS' : 'Send message'}
          >
            {isSending ? (
              <Loader2 className="w-5 h-5 animate-spin" />
            ) : (
              <Send className="w-5 h-5" />
            )}
          </motion.button>
        </div>
        <p className="text-[11px] text-gray-400 dark:text-gray-500 mt-2 px-1">
          {isUploading ? (
            <span className="text-blue-500">Uploading images...</span>
          ) : selectedImages.length > 0 ? (
            <span>{selectedImages.length} image(s) attached — will send as MMS</span>
          ) : (
            'Press Enter to send · Shift+Enter for new line'
          )}
        </p>
      </div>
    </div>
  )
}
