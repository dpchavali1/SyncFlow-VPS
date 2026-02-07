import { create } from 'zustand'

interface Attachment {
  id?: number
  contentType: string
  url?: string           // Firebase Storage download URL
  fileName?: string
  type?: string          // 'image', 'video', 'audio', etc.
  encrypted?: boolean
}

interface Message {
  id: string
  address: string
  body: string
  date: number
  type: number
  timestamp?: number
  contactName?: string
  encrypted?: boolean
  decryptionFailed?: boolean
  e2eeFailed?: boolean         // E2EE encryption failed, sent as plaintext
  e2eeFailureReason?: string   // Reason for E2EE failure
  isMms?: boolean
  attachments?: Attachment[]  // MMS attachments from Firebase
  mmsParts?: any[]            // VPS MMS attachment metadata
  mmsSubject?: string
}

interface ReadReceipt {
  messageId: string
  readAt: number
  readBy: string
  readDeviceName?: string
  conversationAddress?: string
  sourceId?: number
  sourceType?: string
}

interface SpamMessage {
  id: string
  address: string
  body: string
  date: number
  contactName?: string
  spamConfidence?: number
  spamReasons?: string
  detectedAt?: number
  isUserMarked?: boolean
  isRead?: boolean
}

interface Device {
  id: string
  name: string
  type: string
  pairedAt: number
}

type MessageFolder = 'inbox' | 'spam'

interface AppState {
  // Authentication
  userId: string | null
  isAuthenticated: boolean
  setUserId: (userId: string | null) => void

  // Sync Group
  syncGroupId: string | null
  setSyncGroupId: (id: string | null) => void
  deviceCount: number
  deviceLimit: number
  setDeviceInfo: (count: number, limit: number) => void

  // Messages
  messages: Message[]
  setMessages: (messages: Message[]) => void
  readReceipts: Record<string, ReadReceipt>
  setReadReceipts: (receipts: Record<string, ReadReceipt>) => void
  selectedConversation: string | null
  setSelectedConversation: (address: string | null) => void
  spamMessages: SpamMessage[]
  setSpamMessages: (messages: SpamMessage[]) => void
  selectedSpamAddress: string | null
  setSelectedSpamAddress: (address: string | null) => void
  activeFolder: MessageFolder
  setActiveFolder: (folder: MessageFolder) => void

  // Devices
  devices: Device[]
  setDevices: (devices: Device[]) => void

  // UI State
  isSidebarOpen: boolean
  toggleSidebar: () => void
  isPairing: boolean
  setIsPairing: (isPairing: boolean) => void
  isConversationListVisible: boolean
  setIsConversationListVisible: (visible: boolean) => void
  initializeConversationListVisibility: () => void

  // Notifications
  hasNewMessage: boolean
  setHasNewMessage: (hasNew: boolean) => void
}

export const useAppStore = create<AppState>((set) => ({
  // Authentication
  userId: null,
  isAuthenticated: false,
  setUserId: (userId) => set({ userId, isAuthenticated: !!userId }),

  // Sync Group
  syncGroupId: null,
  setSyncGroupId: (id) => set({ syncGroupId: id }),
  deviceCount: 0,
  deviceLimit: 3,
  setDeviceInfo: (count, limit) => set({ deviceCount: count, deviceLimit: limit }),

  // Messages
  messages: [],
  setMessages: (messages) => set({ messages }),
  readReceipts: {},
  setReadReceipts: (receipts) => set({ readReceipts: receipts }),
  selectedConversation: null,
  setSelectedConversation: (address) => set({ selectedConversation: address }),
  spamMessages: [],
  setSpamMessages: (messages) => set({ spamMessages: messages }),
  selectedSpamAddress: null,
  setSelectedSpamAddress: (address) => set({ selectedSpamAddress: address }),
  activeFolder: 'inbox',
  setActiveFolder: (folder) => set({ activeFolder: folder }),

  // Devices
  devices: [],
  setDevices: (devices) => set({ devices }),

  // UI State
  isSidebarOpen: true,
  toggleSidebar: () => set((state) => ({ isSidebarOpen: !state.isSidebarOpen })),
  isPairing: false,
  setIsPairing: (isPairing) => set({ isPairing }),
  isConversationListVisible: true,
  setIsConversationListVisible: (isConversationListVisible) => {
    set({ isConversationListVisible })
    if (typeof window !== 'undefined') {
      localStorage.setItem('syncflow_conversation_list_visible', isConversationListVisible.toString())
    }
  },
  initializeConversationListVisibility: () => {
    if (typeof window !== 'undefined') {
      const saved = localStorage.getItem('syncflow_conversation_list_visible')
      const visible = saved !== 'false' // Default to true
      set({ isConversationListVisible: visible })
    }
  },

  // Notifications
  hasNewMessage: false,
  setHasNewMessage: (hasNew) => set({ hasNewMessage: hasNew }),
}))
