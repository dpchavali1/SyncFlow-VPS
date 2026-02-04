package com.phoneintegration.app.data

import android.content.Context
import com.phoneintegration.app.data.database.AppDatabase
import com.phoneintegration.app.data.database.Group
import com.phoneintegration.app.data.database.GroupMember
import com.phoneintegration.app.data.database.GroupWithMembers
import com.phoneintegration.app.ui.conversations.ContactInfo
import com.phoneintegration.app.desktop.DesktopSyncService
import kotlinx.coroutines.flow.Flow
import android.util.Log

class GroupRepository(private val context: Context) {

    private val groupDao = AppDatabase.getInstance(context).groupDao()
    private val syncService = DesktopSyncService(context)

    // Get all groups with members
    fun getAllGroupsWithMembers(): Flow<List<GroupWithMembers>> {
        return groupDao.getAllGroupsWithMembers()
    }

    // Create a new group with members
    suspend fun createGroup(
        name: String,
        members: List<ContactInfo>
    ): Long {
        // Create group
        val group = Group(name = name)
        val groupId = groupDao.insertGroup(group)

        // Add members
        val groupMembers = mutableListOf<GroupMember>()
        members.forEach { contact ->
            val member = GroupMember(
                groupId = groupId,
                phoneNumber = contact.phoneNumber,
                contactName = contact.name
            )
            groupDao.insertGroupMember(member)
            groupMembers.add(member)
        }

        // Sync to Firebase
        try {
            val createdGroup = groupDao.getGroupById(groupId)
            if (createdGroup != null) {
                syncService.syncGroup(createdGroup, groupMembers)
                Log.d("GroupRepository", "Group synced to Firebase: $groupId")
            }
        } catch (e: Exception) {
            Log.e("GroupRepository", "Failed to sync group to Firebase", e)
        }

        return groupId
    }

    // Get group by ID
    suspend fun getGroupById(groupId: Long): Group? {
        return groupDao.getGroupById(groupId)
    }

    // Get group with members
    suspend fun getGroupWithMembers(groupId: Long): GroupWithMembers? {
        return groupDao.getGroupWithMembers(groupId)
    }

    // Update group thread ID after sending first message
    suspend fun updateGroupThreadId(groupId: Long, threadId: Long) {
        val group = groupDao.getGroupById(groupId)
        group?.let {
            val updatedGroup = it.copy(threadId = threadId)
            groupDao.updateGroup(updatedGroup)

            // Sync to Firebase
            try {
                val members = groupDao.getGroupMembers(groupId)
                syncService.syncGroup(updatedGroup, members)
                Log.d("GroupRepository", "Group thread ID updated and synced to Firebase: $groupId")
            } catch (e: Exception) {
                Log.e("GroupRepository", "Failed to sync group update to Firebase", e)
            }
        }
    }

    // Get group by thread ID
    suspend fun getGroupByThreadId(threadId: Long): Group? {
        return groupDao.getGroupByThreadId(threadId)
    }

    // Update last message timestamp
    suspend fun updateLastMessage(groupId: Long) {
        val group = groupDao.getGroupById(groupId)
        group?.let {
            groupDao.updateGroup(it.copy(lastMessageAt = System.currentTimeMillis()))
        }
    }

    // Delete group
    suspend fun deleteGroup(groupId: Long) {
        val group = groupDao.getGroupById(groupId)
        group?.let {
            groupDao.deleteAllGroupMembers(groupId)
            groupDao.deleteGroup(it)

            // Delete from Firebase
            try {
                syncService.deleteGroup(groupId)
                Log.d("GroupRepository", "Group deleted from Firebase: $groupId")
            } catch (e: Exception) {
                Log.e("GroupRepository", "Failed to delete group from Firebase", e)
            }
        }
    }

    // Get group members
    suspend fun getGroupMembers(groupId: Long): List<GroupMember> {
        return groupDao.getGroupMembers(groupId)
    }
}
