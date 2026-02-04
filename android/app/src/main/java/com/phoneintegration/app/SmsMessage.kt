package com.phoneintegration.app

data class MmsAttachment(
    val id: Long,
    val contentType: String,
    val filePath: String?,
    val data: ByteArray? = null,
    val fileName: String? = null
) {
    fun isImage(): Boolean = contentType.startsWith("image/")
    fun isVideo(): Boolean = contentType.startsWith("video/")
    fun isAudio(): Boolean = contentType.startsWith("audio/")
    fun isVCard(): Boolean = contentType == "text/x-vcard" || contentType == "text/vcard"
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MmsAttachment
        if (id != other.id) return false
        if (contentType != other.contentType) return false
        if (filePath != other.filePath) return false
        if (data != null) {
            if (other.data == null) return false
            if (!data.contentEquals(other.data)) return false
        } else if (other.data != null) return false
        if (fileName != other.fileName) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (filePath?.hashCode() ?: 0)
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + (fileName?.hashCode() ?: 0)
        return result
    }
}

data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    var contactName: String? = null,
    var category: MessageCategory? = null,
    var otpInfo: OtpInfo? = null,
    val isMms: Boolean = false,
    val mmsAttachments: List<MmsAttachment> = emptyList(),
    val mmsSubject: String? = null,
    val subId: Int? = null,  // Subscription ID (SIM card identifier)
    var isE2ee: Boolean = false,
    val isRead: Boolean = true  // Whether message has been read (unread from unknown = suspicious)
) {
    fun getFormattedTime(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(date))
    }

    fun getDisplayName(): String {
        return contactName ?: address
    }
    
    fun hasAttachments(): Boolean = mmsAttachments.isNotEmpty()
    
    fun getAttachmentCount(): Int = mmsAttachments.size
}

