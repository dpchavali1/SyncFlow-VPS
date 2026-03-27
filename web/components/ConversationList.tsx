'use client'

import { useMemo, useState, useRef, useEffect, useCallback, memo } from 'react'
import { Search, User, GripVertical, X, Lock, Unlock, Inbox, ShieldAlert, MessageCircle } from 'lucide-react'
import { motion, AnimatePresence } from 'framer-motion'
import { useAppStore } from '@/lib/store'
import { format, isToday, isYesterday } from 'date-fns'
import { ConversationListSkeleton } from './SkeletonLoaders'
import { normalizePhoneForConversation } from '@/lib/phoneNumberNormalizer'
import { staggerContainer, staggerItem } from '@/lib/animations'

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

// Debounce hook
function useDebounce<T>(value: T, delay: number): T {
  const [debouncedValue, setDebouncedValue] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedValue(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])

  return debouncedValue
}

function formatTimestamp(timestamp: number): string {
  const date = new Date(timestamp)
  if (isToday(date)) return format(date, 'h:mm a')
  if (isYesterday(date)) return 'Yesterday'
  return format(date, 'MMM d')
}

// Avatar color generator from name
function getAvatarGradient(name: string): string {
  const gradients = [
    'from-blue-400 to-blue-600',
    'from-violet-400 to-purple-600',
    'from-emerald-400 to-teal-600',
    'from-orange-400 to-red-500',
    'from-pink-400 to-rose-600',
    'from-cyan-400 to-blue-500',
    'from-amber-400 to-orange-500',
    'from-indigo-400 to-violet-600',
  ]
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash)
  }
  return gradients[Math.abs(hash) % gradients.length]
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
    ? (showDecryptWarning ? 'text-amber-500' : 'text-emerald-500')
    : (showEncryptWarning ? 'text-amber-500' : 'text-surface-400 dark:text-surface-500')

  const displayName = conv.contactName || conv.address
  const gradient = getAvatarGradient(displayName)

  return (
    <div
      onClick={onClick}
      role="option"
      aria-selected={isSelected}
      tabIndex={0}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick() } }}
      className={`group mx-2 my-0.5 px-3 py-3 rounded-xl cursor-pointer transition-all duration-200 hover:scale-[1.005] active:scale-[0.995] ${
        isSelected
          ? 'bg-primary-500/10 dark:bg-primary-500/15 shadow-sm'
          : 'hover:bg-surface-100/80 dark:hover:bg-surface-700/40'
      }`}
    >
      <div className="flex items-center gap-3">
        {/* Avatar */}
        <div className={`w-11 h-11 rounded-full bg-gradient-to-br ${gradient} flex items-center justify-center text-white font-semibold text-sm flex-shrink-0 shadow-sm ${isSelected ? 'ring-2 ring-primary-400/50 ring-offset-1 ring-offset-transparent' : ''}`}>
          {displayName.charAt(0).toUpperCase()}
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-center justify-between mb-0.5">
            <h3 className={`font-semibold text-[14px] truncate ${isSelected ? 'text-primary-700 dark:text-primary-300' : 'text-surface-900 dark:text-surface-50'}`}>
              {displayName}
            </h3>
            <div className="flex items-center gap-1.5 ml-2 flex-shrink-0">
              <span title={encryptionTitle} className="inline-flex">
                <EncryptionIcon className={`w-3 h-3 ${encryptionClass}`} />
              </span>
              <span className={`text-[11px] ${isSelected ? 'text-primary-500 dark:text-primary-400' : 'text-surface-400 dark:text-surface-500'}`}>
                {formatTimestamp(conv.timestamp)}
              </span>
            </div>
          </div>

          <div className="flex items-center justify-between">
            <p className="text-[13px] text-surface-500 dark:text-surface-400 truncate pr-2">
              {conv.lastMessage}
            </p>
            {conv.unreadCount > 0 && (
              <span className="min-w-[20px] h-5 px-1.5 rounded-full bg-primary-500 text-white text-[11px] font-bold flex items-center justify-center flex-shrink-0 shadow-glow/30">
                {conv.unreadCount}
              </span>
            )}
          </div>
        </div>
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
  const displayName = conv.contactName || conv.address

  return (
    <div
      onClick={onClick}
      role="option"
      aria-selected={isSelected}
      tabIndex={0}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick() } }}
      className={`group mx-2 my-0.5 px-3 py-3 rounded-xl cursor-pointer transition-all duration-200 hover:scale-[1.005] active:scale-[0.995] ${
        isSelected
          ? 'bg-red-500/10 dark:bg-red-500/15 shadow-sm'
          : 'hover:bg-surface-100/80 dark:hover:bg-surface-700/40'
      }`}
    >
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-full bg-gradient-to-br from-red-300 to-red-500 dark:from-red-500/60 dark:to-red-700/60 flex items-center justify-center text-white font-semibold text-sm flex-shrink-0">
          {displayName.charAt(0).toUpperCase()}
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-center justify-between mb-0.5">
            <h3 className="font-semibold text-[14px] text-surface-900 dark:text-surface-50 truncate">
              {displayName}
            </h3>
            <span className="text-[11px] text-surface-400 dark:text-surface-500 ml-2 flex-shrink-0">
              {formatTimestamp(conv.timestamp)}
            </span>
          </div>

          <div className="flex items-center justify-between">
            <p className="text-[13px] text-surface-500 dark:text-surface-400 truncate pr-2">
              {conv.lastMessage}
            </p>
            <span className="text-[11px] text-red-600 dark:text-red-400 font-semibold px-2 py-0.5 bg-red-50 dark:bg-red-900/30 rounded-full flex-shrink-0">
              {conv.count}
            </span>
          </div>
        </div>
      </div>
    </div>
  )
})

// Folder tab data
const FOLDERS = [
  { id: 'inbox' as const, label: 'Inbox', icon: Inbox, activeColor: 'bg-primary-500 text-white shadow-glow/20' },
  { id: 'spam' as const, label: 'Spam', icon: ShieldAlert, activeColor: 'bg-red-500 text-white shadow-sm' },
]

interface ConversationListProps {
  onConversationSelect?: () => void
}

export default function ConversationList({ onConversationSelect }: ConversationListProps) {
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
  const [width, setWidth] = useState(340)
  const [isResizing, setIsResizing] = useState(false)
  const [isInitialLoading, setIsInitialLoading] = useState(true)
  const [isMobile, setIsMobile] = useState(false)
  const resizeRef = useRef<HTMLDivElement>(null)

  // Track mobile viewport
  useEffect(() => {
    if (typeof window === 'undefined') return
    const mql = window.matchMedia('(max-width: 767px)')
    setIsMobile(mql.matches)
    const handler = (e: MediaQueryListEvent) => setIsMobile(e.matches)
    mql.addEventListener('change', handler)
    return () => mql.removeEventListener('change', handler)
  }, [])

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

  const MIN_WIDTH = 280
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
      const normalized = normalizePhoneForConversation(msg.address)
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
    // On mobile viewports, switch to message view
    if (typeof window !== 'undefined' && window.innerWidth < 768) {
      useAppStore.getState().setIsMobileShowingMessages(true)
    }
    onConversationSelect?.()
  }, [setSelectedConversation, onConversationSelect])

  const handleSpamClick = useCallback((address: string) => {
    setSelectedSpamAddress(address)
    if (typeof window !== 'undefined' && window.innerWidth < 768) {
      useAppStore.getState().setIsMobileShowingMessages(true)
    }
    onConversationSelect?.()
  }, [setSelectedSpamAddress, onConversationSelect])

  const handleFolderClick = useCallback((folder: 'inbox' | 'spam') => {
    setActiveFolder(folder)
    if (folder !== 'inbox') setSelectedConversation(null)
    if (folder !== 'spam') setSelectedSpamAddress(null)
  }, [setActiveFolder, setSelectedConversation, setSelectedSpamAddress])

  if (!isSidebarOpen) {
    return null
  }

  return (
    <div
      className={`relative glass-panel border-r-0 rounded-r-none flex flex-col min-h-0 overflow-hidden ${isMobile ? 'w-full' : ''}`}
      style={isMobile ? undefined : { width: `${width}px`, minWidth: `${MIN_WIDTH}px`, maxWidth: `${MAX_WIDTH}px` }}
    >
      {/* Header area */}
      <div className="flex-shrink-0 px-4 pt-4 pb-3">
        {/* Close button */}
        <div className="flex justify-end mb-2">
          <motion.button
            onClick={() => setIsConversationListVisible(false)}
            className="p-1.5 text-surface-400 hover:text-surface-600 dark:hover:text-surface-300 rounded-lg hover:bg-surface-100 dark:hover:bg-surface-700/50 transition-colors"
            whileTap={{ scale: 0.9 }}
            title="Hide conversation list"
          >
            <X className="w-4 h-4" />
          </motion.button>
        </div>

        {/* Folder tabs — segmented control */}
        <div className="flex items-center gap-1 p-1 rounded-xl bg-surface-100/80 dark:bg-surface-800/60 mb-3">
          {FOLDERS.map((folder) => {
            const isActive = activeFolder === folder.id
            const Icon = folder.icon
            return (
              <motion.button
                key={folder.id}
                onClick={() => handleFolderClick(folder.id)}
                className={`relative flex-1 flex items-center justify-center gap-1.5 px-2 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                  isActive
                    ? folder.activeColor
                    : 'text-surface-500 dark:text-surface-400 hover:text-surface-700 dark:hover:text-surface-300'
                }`}
                whileTap={{ scale: 0.97 }}
              >
                <Icon className="w-3.5 h-3.5" />
                <span>{folder.label}</span>
                {folder.id === 'spam' && spamMessages.length > 0 && !isActive && (
                  <span className="ml-0.5 text-[10px] text-red-500 font-bold">{spamMessages.length}</span>
                )}
              </motion.button>
            )
          })}
        </div>

        {/* Search */}
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-surface-400" />
          <input
            id="conversation-search"
            type="text"
            placeholder="Search..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            aria-label="Search conversations"
            className="w-full pl-9 pr-4 py-2 rounded-xl glass-input text-sm text-surface-900 dark:text-surface-50 placeholder:text-surface-400"
          />
          {searchQuery && (
            <motion.button
              initial={{ opacity: 0, scale: 0.8 }}
              animate={{ opacity: 1, scale: 1 }}
              onClick={() => setSearchQuery('')}
              className="absolute right-2.5 top-1/2 -translate-y-1/2 p-0.5 rounded-full hover:bg-surface-200 dark:hover:bg-surface-600 transition-colors"
            >
              <X className="w-3.5 h-3.5 text-surface-400" />
            </motion.button>
          )}
        </div>
      </div>

      {/* Conversation list */}
      <div className="flex-1 min-h-0 h-0 overflow-y-auto py-1" role="listbox" aria-label="Conversations">
        {isInitialLoading ? (
          <ConversationListSkeleton />
        ) : activeFolder === 'spam' ? (
          filteredSpamConversations.length === 0 ? (
            <EmptyState
              icon={ShieldAlert}
              title={debouncedSearchQuery ? 'No spam matches' : 'No spam messages'}
              subtitle={debouncedSearchQuery ? 'Try a different search term' : 'Spam messages from your phone will appear here'}
            />
          ) : (
            <motion.div variants={staggerContainer} initial="hidden" animate="visible">
              {filteredSpamConversations.map((conv) => (
                <motion.div key={conv.address} variants={staggerItem}>
                  <SpamConversationItem
                    conv={conv}
                    isSelected={selectedSpamAddress === conv.address}
                    onClick={() => handleSpamClick(conv.address)}
                  />
                </motion.div>
              ))}
            </motion.div>
          )
        ) : filteredConversations.length === 0 ? (
          <EmptyState
            icon={MessageCircle}
            title={debouncedSearchQuery ? 'No matches found' : 'No messages yet'}
            subtitle={debouncedSearchQuery ? 'Try a different search term' : 'Messages from your phone will appear here'}
          />
        ) : (
          <motion.div variants={staggerContainer} initial="hidden" animate="visible">
            {filteredConversations.map((conv) => (
              <motion.div key={conv.normalizedAddress} variants={staggerItem}>
                <ConversationItem
                  conv={conv}
                  isSelected={selectedConversation === conv.normalizedAddress}
                  onClick={() => handleConversationClick(conv.normalizedAddress)}
                />
              </motion.div>
            ))}
          </motion.div>
        )}
      </div>

      {/* Resize handle */}
      <div
        ref={resizeRef}
        onMouseDown={() => setIsResizing(true)}
        className="absolute top-0 right-0 w-1.5 h-full cursor-col-resize group flex items-center justify-center z-10"
      >
        <div className={`absolute inset-y-0 right-0 w-0.5 transition-colors duration-200 ${isResizing ? 'bg-primary-500' : 'bg-transparent group-hover:bg-primary-400/50'}`} />
        <div className="absolute right-0 top-1/2 -translate-y-1/2 w-4 h-8 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
          <GripVertical className="w-3 h-3 text-surface-400" />
        </div>
      </div>
    </div>
  )
}

// Empty state component
function EmptyState({ icon: Icon, title, subtitle }: { icon: React.ElementType; title: string; subtitle: string }) {
  return (
    <div className="flex flex-col items-center justify-center h-full p-8 text-center">
      <motion.div
        className="w-16 h-16 rounded-2xl bg-surface-100 dark:bg-surface-800 flex items-center justify-center mb-4"
        animate={{ y: [0, -4, 0] }}
        transition={{ duration: 3, repeat: Infinity, ease: 'easeInOut' }}
      >
        <Icon className="w-7 h-7 text-surface-300 dark:text-surface-600" />
      </motion.div>
      <p className="text-sm font-medium text-surface-500 dark:text-surface-400 mb-1">
        {title}
      </p>
      <p className="text-xs text-surface-400 dark:text-surface-500">
        {subtitle}
      </p>
    </div>
  )
}
