package com.phoneintegration.app.utils

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import com.phoneintegration.app.MainActivity

object DefaultSmsHelper {

    /**
     * Check if this app is the default SMS app
     */
    fun isDefaultSmsApp(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            rm.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }
    }

    /**
     * Request to become default SMS app (calls MainActivity's launcher)
     */
    fun requestDefaultSmsApp(activity: MainActivity) {
        activity.requestDefaultSmsAppViaRole()
    }

    /**
     * Fallback picker for Android < 10
     */
    fun openSmsAppPicker(context: Context) {
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
