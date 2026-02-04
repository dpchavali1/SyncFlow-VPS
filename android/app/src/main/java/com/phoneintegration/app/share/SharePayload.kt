package com.phoneintegration.app.share

import android.net.Uri

data class SharePayload(
    val text: String?,
    val uris: List<Uri>,
    val recipient: String? = null
) {
    val hasContent: Boolean
        get() = !text.isNullOrBlank() || uris.isNotEmpty() || !recipient.isNullOrBlank()
}
