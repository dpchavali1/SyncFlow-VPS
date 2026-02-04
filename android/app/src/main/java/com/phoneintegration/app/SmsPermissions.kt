package com.phoneintegration.app

import android.app.role.RoleManager
import android.content.Context
import android.os.Build

object SmsPermissions {

    fun isDefaultSmsApp(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            val defaultSmsPackage = android.provider.Telephony.Sms.getDefaultSmsPackage(context)
            defaultSmsPackage == context.packageName
        }
    }
}
