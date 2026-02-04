package com.phoneintegration.app

data class ConversationInfo(
    val threadId: Long,
    val address: String,  // For groups: comma-separated addresses
    var contactName: String? = null,  // For groups: comma-separated names
    var lastMessage: String,
    val timestamp: Long,
    var unreadCount: Int = 0,
    var photoUri: String? = null,
    var isAdConversation: Boolean = false,
    var isGroupConversation: Boolean = false,  // indicates if this is a group chat
    var recipientCount: Int = 1,  // number of recipients in group
    var groupId: Long? = null,  // database group ID for saved groups
    var isE2ee: Boolean = false,
    var isArchived: Boolean = false,  // indicates if conversation is archived
    var hasDraft: Boolean = false,  // indicates if there's a pending draft
    var hasScheduled: Boolean = false,  // indicates if there are scheduled messages
    var isPinned: Boolean = false,  // indicates if conversation is pinned to top
    var isMuted: Boolean = false,  // indicates if notifications are muted
    var relatedThreadIds: List<Long> = emptyList(),  // all thread IDs for this contact (for merged conversations)
    var relatedAddresses: List<String> = emptyList()  // all addresses for this contact (for merged conversations)
)
