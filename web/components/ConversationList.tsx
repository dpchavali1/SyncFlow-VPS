'use client'

import { useMemo, useState, useRef, useEffect, useCallback, memo } from 'react'
import { Search, User, GripVertical, X, Lock, Unlock } from 'lucide-react'
import { useAppStore } from '@/lib/store'
import { format } from 'date-fns'
import { ConversationListSkeleton } from './SkeletonLoaders'

interface Conversation {
  address: string
  normalizedAddress: string
  allAddresses: string[]
  contactName?: string
  lastMessage: string
  timestamp: number
  unreadCount: number
  lastMessageEncrypted?: boolean
  lastMessageE2eeFailed?: boolean
  lastMessageDecryptionFailed?: boolean
}

// LRU Cache for phone number normalization
const normalizationCache = new Map<string, string>()
const MAX_CACHE_SIZE = 1000

function normalizePhoneNumber(address: string): string {
  // Check cache first
  const cached = normalizationCache.get(address)
  if (cached !== undefined) return cached

  let result: string
  // Skip non-phone addresses (email, short codes, etc.)
  if (address.includes('@') || address.length < 6) {
    result = address.toLowerCase()
  } else {
    // Remove all non-digit characters
    const digitsOnly = address.replace(/[^0-9]/g, '')
    // For comparison, use last 10 digits (handles country code differences)
    result = digitsOnly.length >= 10 ? digitsOnly.slice(-10) : digitsOnly
  }

  // Add to cache with LRU eviction
  if (normalizationCache.size >= MAX_CACHE_SIZE) {
    const firstKey = normalizationCache.keys().next().value
    if (firstKey) normalizationCache.delete(firstKey)
  }
  normalizationCache.set(address, result)
  return result
}

// Debounce hook
function useDebounce<T>(value: T, delay: number): T {
  const [debouncedValue, setDebouncedValue] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedValue(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])

  return debouncedValue
}

// Memoized conversation item component
const ConversationItem = memo(function ConversationItem({
  conv,
  isSelected,
  onClick,
}: {
  conv: Conversation
  isSelected: boolean
  onClick: () => void
}) {
  const showEncrypted = !!conv.lastMessageEncrypted
  const showDecryptWarning = !!conv.lastMessageDecryptionFailed
  const showEncryptWarning = !!conv.lastMessageE2eeFailed
  const EncryptionIcon = showEncrypted ? Lock : Unlock
  const encryptionTitle = showEncrypted
    ? (showDecryptWarning ? 'Encrypted (keys missing)' : 'Encrypted')
    : (showEncryptWarning ? 'Not encrypted (E2EE failed)' : 'Not encrypted')
  const encryptionClass = showEncrypted
    ? (showDecryptWarning ? 'text-amber-600 dark:text-amber-400' : 'text-emerald-600 dark:text-emerald-400')
    : (showEncryptWarning ? 'text-amber-600 dark:text-amber-400' : 'text-gray-400 dark:text-gray-500')

  return (
    <div
      onClick={onClick}
      className={`p-4 border-b border-gray-100 dark:border-gray-700 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors ${
        isSelected
          ? 'bg-blue-50 dark:bg-blue-900/20 border-l-4 border-l-blue-600'
          : ''
      }`}
    >
      <div className="flex items-start gap-3">
        <div className="w-12 h-12 rounded-full bg-gradient-to-br from-blue-400 to-blue-600 flex items-center justify-center text-white font-semibold flex-shrink-0">
          {(conv.contactName || conv.address).charAt(0).toUpperCase()}
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-baseline justify-between mb-1">
            <h3 className="font-semibold text-gray-900 dark:text-white truncate">
              {conv.contactName || conv.address}
            </h3>
            <div className="flex items-center gap-2 ml-2 flex-shrink-0">
              <span title={encryptionTitle} className="inline-flex">
                <EncryptionIcon className={`w-3.5 h-3.5 ${encryptionClass}`} />
              </span>
              <span className="text-xs text-gray-500 dark:text-gray-400">
                {format(new Date(conv.timestamp), 'MMM d')}
              </span>
            </div>
          </div>

          <p className="text-sm text-gray-600 dark:text-gray-400 truncate">
            {conv.lastMessage}
          </p>
        </div>

        {conv.unreadCount > 0 && (
          <div className="w-6 h-6 rounded-full bg-blue-600 text-white text-xs flex items-center justify-center flex-shrink-0">
            {conv.unreadCount}
          </div>
        )}
      </div>
    </div>
  )
})

// Memoized spam conversation item
const SpamConversationItem = memo(function SpamConversationItem({
  conv,
  isSelected,
  onClick,
}: {
  conv: { address: string; contactName?: string; lastMessage: string; timestamp: number; count: number }
  isSelected: boolean
  onClick: () => void
}) {
  return (
    <div
      onClick={onClick}
      className={`p-4 border-b border-gray-100 dark:border-gray-700 cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors ${
        isSelected
          ? 'bg-red-50 dark:bg-red-900/20 border-l-4 border-l-red-600'
          : ''
      }`}
    >
      <div className="flex items-start gap-3">
        <div className="w-12 h-12 rounded-full bg-red-100 dark:bg-red-900/40 flex items-center justify-center text-red-600 font-semibold flex-shrink-0">
          {(conv.contactName || conv.address).charAt(0).toUpperCase()}
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-baseline justify-between mb-1">
            <h3 className="font-semibold text-gray-900 dark:text-white truncate">
              {conv.contactName || conv.address}
            </h3>
            <span className="text-xs text-gray-500 dark:text-gray-400 ml-2 flex-shrink-0">
              {format(new Date(conv.timestamp), 'MMM d')}
            </span>
          </div>

          <p className="text-sm text-gray-600 dark:text-gray-400 truncate">
            {conv.lastMessage}
          </p>
        </div>

        <div className="text-xs text-red-600 font-semibold px-2 py-1 bg-red-50 dark:bg-red-900/30 rounded-full">
          {conv.count}
        </div>
      </div>
    </div>
  )
})

export default function ConversationList() {
  // Use selectors to minimize re-renders
  const messages = useAppStore((state) => state.messages)
  const readReceipts = useAppStore((state) => state.readReceipts)
  const selectedConversation = useAppStore((state) => state.selectedConversation)
  const setSelectedConversation = useAppStore((state) => state.setSelectedConversation)
  const spamMessages = useAppStore((state) => state.spamMessages)
  const selectedSpamAddress = useAppStore((state) => state.selectedSpamAddress)
  const setSelectedSpamAddress = useAppStore((state) => state.setSelectedSpamAddress)
  const activeFolder = useAppStore((state) => state.activeFolder)
  const setActiveFolder = useAppStore((state) => state.setActiveFolder)
  const isSidebarOpen = useAppStore((state) => state.isSidebarOpen)
  const setIsConversationListVisible = useAppStore((state) => state.setIsConversationListVisible)

  const [searchQuery, setSearchQuery] = useState('')
  const [width, setWidth] = useState(320)
  const [isResizing, setIsResizing] = useState(false)
  const [isInitialLoading, setIsInitialLoading] = useState(true)
  const resizeRef = useRef<HTMLDivElement>(null)

  // Debounce search query for better performance
  const debouncedSearchQuery = useDebounce(searchQuery, 150)

  // Track initial loading state - show skeleton for first 3 seconds or until messages arrive
  useEffect(() => {
    if (messages.length > 0) {
      setIsInitialLoading(false)
    } else {
      const timer = setTimeout(() => setIsInitialLoading(false), 3000)
      return () => clearTimeout(timer)
    }
  }, [messages.length])

  const MIN_WIDTH = 250
  const MAX_WIDTH = 500

  const readReceiptsBaseline = useMemo(() => {
    if (typeof window === 'undefined') return 0
    const key = 'syncflow_read_receipts_baseline'
    const existing = localStorage.getItem(key)
    if (existing) {
      const parsed = Number(existing)
      return Number.isFinite(parsed) ? parsed : 0
    }
    const now = Date.now()
    localStorage.setItem(key, String(now))
    return now
  }, [])

  // Handle resize
  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!isResizing) return
      const newWidth = Math.min(Math.max(e.clientX, MIN_WIDTH), MAX_WIDTH)
      setWidth(newWidth)
    }

    const handleMouseUp = () => {
      setIsResizing(false)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }

    if (isResizing) {
      document.body.style.cursor = 'col-resize'
      document.body.style.userSelect = 'none'
      document.addEventListener('mousemove', handleMouseMove)
      document.addEventListener('mouseup', handleMouseUp)
    }

    return () => {
      document.removeEventListener('mousemove', handleMouseMove)
      document.removeEventListener('mouseup', handleMouseUp)
    }
  }, [isResizing])

  // Group messages by normalized address - optimized with cached normalization
  const conversations = useMemo(() => {
    const convMap = new Map<string, Conversation>()

    for (const msg of messages) {
      const normalized = normalizePhoneNumber(msg.address)
      const existing = convMap.get(normalized)
      const isUnread =
        msg.type === 1 &&
        msg.date >= readReceiptsBaseline &&
        !readReceipts[msg.id]

      if (!existing) {
        convMap.set(normalized, {
          address: msg.address,
          normalizedAddress: normalized,
          allAddresses: [msg.address],
          contactName: msg.contactName,
          lastMessage: msg.body,
          timestamp: msg.date,
          unreadCount: isUnread ? 1 : 0,
          lastMessageEncrypted: !!msg.encrypted,
          lastMessageE2eeFailed: !!msg.e2eeFailed,
          lastMessageDecryptionFailed: !!msg.decryptionFailed,
        })
      } else {
        if (msg.date > existing.timestamp) {
          existing.lastMessage = msg.body
          existing.timestamp = msg.date
          existing.lastMessageEncrypted = !!msg.encrypted
          existing.lastMessageE2eeFailed = !!msg.e2eeFailed
          existing.lastMessageDecryptionFailed = !!msg.decryptionFailed
          if (msg.contactName && !existing.contactName) {
            existing.contactName = msg.contactName
          }
        }
        if (!existing.allAddresses.includes(msg.address)) {
          existing.allAddresses.push(msg.address)
        }
        if (isUnread) {
          existing.unreadCount += 1
        }
      }
    }

    return Array.from(convMap.values()).sort((a, b) => b.timestamp - a.timestamp)
  }, [messages, readReceipts, readReceiptsBaseline])

  const spamConversations = useMemo(() => {
    const grouped = new Map<string, { address: string; contactName?: string; lastMessage: string; timestamp: number; count: number }>()

    for (const msg of spamMessages) {
      const existing = grouped.get(msg.address)
      if (!existing) {
        grouped.set(msg.address, {
          address: msg.address,
          contactName: msg.contactName,
          lastMessage: msg.body,
          timestamp: msg.date,
          count: 1,
        })
      } else {
        existing.count += 1
        if (msg.date > existing.timestamp) {
          existing.lastMessage = msg.body
          existing.timestamp = msg.date
          if (!existing.contactName && msg.contactName) {
            existing.contactName = msg.contactName
          }
        }
      }
    }
    return Array.from(grouped.values()).sort((a, b) => b.timestamp - a.timestamp)
  }, [spamMessages])

  // Filter with debounced search query
  const filteredConversations = useMemo(() => {
    if (!debouncedSearchQuery.trim()) return conversations

    const query = debouncedSearchQuery.toLowerCase()
    const queryDigits = query.replace(/[^0-9]/g, '')

    return conversations.filter(conv => {
      if (conv.contactName?.toLowerCase().includes(query)) return true
      if (conv.address.toLowerCase().includes(query)) return true
      if (conv.lastMessage.toLowerCase().includes(query)) return true

      if (queryDigits.length > 0) {
        const addressDigits = conv.address.replace(/[^0-9]/g, '')
        const normalized = conv.normalizedAddress
        return addressDigits.includes(queryDigits) ||
          queryDigits.includes(addressDigits) ||
          normalized.includes(queryDigits) ||
          queryDigits.includes(normalized)
      }
      return false
    })
  }, [conversations, debouncedSearchQuery])

  const filteredSpamConversations = useMemo(() => {
    if (!debouncedSearchQuery.trim()) return spamConversations
    const query = debouncedSearchQuery.toLowerCase()
    const queryDigits = query.replace(/[^0-9]/g, '')
    return spamConversations.filter(conv =>
      (conv.contactName?.toLowerCase().includes(query)) ||
      conv.address.toLowerCase().includes(query) ||
      conv.lastMessage.toLowerCase().includes(query) ||
      (queryDigits.length > 0 && conv.address.replace(/[^0-9]/g, '').includes(queryDigits))
    )
  }, [spamConversations, debouncedSearchQuery])

  // Memoized click handlers
  const handleConversationClick = useCallback((normalizedAddress: string) => {
    setSelectedConversation(normalizedAddress)
  }, [setSelectedConversation])

  const handleSpamClick = useCallback((address: string) => {
    setSelectedSpamAddress(address)
  }, [setSelectedSpamAddress])

  const handleInboxClick = useCallback(() => {
    setActiveFolder('inbox')
    setSelectedSpamAddress(null)
  }, [setActiveFolder, setSelectedSpamAddress])

  const handleSpamFolderClick = useCallback(() => {
    setActiveFolder('spam')
    setSelectedConversation(null)
  }, [setActiveFolder, setSelectedConversation])

  if (!isSidebarOpen) {
    return null
  }

  return (
    <div
      className="relative bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 flex flex-col min-h-0 overflow-hidden"
      style={{ width: `${width}px`, minWidth: `${MIN_WIDTH}px`, maxWidth: `${MAX_WIDTH}px` }}
    >
      {/* Search */}
      <div className="flex-shrink-0 p-4 border-b border-gray-200 dark:border-gray-700">
        <div className="flex justify-end mb-3">
          <button
            onClick={() => setIsConversationListVisible(false)}
            className="p-1 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 rounded hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
            title="Hide conversation list"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
        <div className="flex items-center gap-2 mb-3">
          <button
            onClick={handleInboxClick}
            className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
              activeFolder === 'inbox'
                ? 'bg-blue-600 text-white'
                : 'bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300'
            }`}
          >
            Inbox
          </button>
          <button
            onClick={handleSpamFolderClick}
            className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
              activeFolder === 'spam'
                ? 'bg-red-600 text-white'
                : 'bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300'
            }`}
          >
            Spam {spamMessages.length > 0 ? `(${spamMessages.length})` : ''}
          </button>
        </div>
        <div className="relative">
          <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-5 h-5 text-gray-400" />
          <input
            type="text"
            placeholder="Search conversations..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-10 pr-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-gray-50 dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
        </div>
      </div>

      {/* Conversations */}
      <div className="flex-1 min-h-0 h-0 overflow-y-auto">
        {isInitialLoading ? (
          <ConversationListSkeleton />
        ) : activeFolder === 'spam' ? (
          filteredSpamConversations.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-full p-8 text-center">
              <User className="w-16 h-16 text-gray-300 dark:text-gray-600 mb-4" />
              <p className="text-gray-500 dark:text-gray-400 mb-2">
                {debouncedSearchQuery ? 'No spam matches found' : 'No spam messages'}
              </p>
              <p className="text-sm text-gray-400 dark:text-gray-500">
                Spam messages from your phone will appear here
              </p>
            </div>
          ) : (
            filteredSpamConversations.map((conv) => (
              <SpamConversationItem
                key={conv.address}
                conv={conv}
                isSelected={selectedSpamAddress === conv.address}
                onClick={() => handleSpamClick(conv.address)}
              />
            ))
          )
        ) : filteredConversations.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full p-8 text-center">
            <User className="w-16 h-16 text-gray-300 dark:text-gray-600 mb-4" />
            <p className="text-gray-500 dark:text-gray-400 mb-2">
              {debouncedSearchQuery ? 'No matches found' : 'No messages yet'}
            </p>
            <p className="text-sm text-gray-400 dark:text-gray-500">
              {debouncedSearchQuery ? 'Try a different search term' : 'Messages from your phone will appear here'}
            </p>
          </div>
        ) : (
          filteredConversations.map((conv) => (
            <ConversationItem
              key={conv.normalizedAddress}
              conv={conv}
              isSelected={selectedConversation === conv.normalizedAddress}
              onClick={() => handleConversationClick(conv.normalizedAddress)}
            />
          ))
        )}
      </div>

      {/* Resize handle */}
      <div
        ref={resizeRef}
        onMouseDown={() => setIsResizing(true)}
        className="absolute top-0 right-0 w-1 h-full cursor-col-resize hover:bg-blue-500 transition-colors group flex items-center justify-center"
        style={{ backgroundColor: isResizing ? 'rgb(59, 130, 246)' : 'transparent' }}
      >
        <div className="absolute right-0 top-1/2 -translate-y-1/2 w-4 h-8 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
          <GripVertical className="w-3 h-3 text-gray-400" />
        </div>
      </div>
    </div>
  )
}
