package com.phoneintegration.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    // Groups
    @Insert
    suspend fun insertGroup(group: Group): Long

    @Update
    suspend fun updateGroup(group: Group)

    @Delete
    suspend fun deleteGroup(group: Group)

    @Query("SELECT * FROM groups ORDER BY lastMessageAt DESC")
    fun getAllGroups(): Flow<List<Group>>

    @Query("SELECT * FROM groups WHERE id = :groupId")
    suspend fun getGroupById(groupId: Long): Group?

    @Query("SELECT * FROM groups WHERE threadId = :threadId")
    suspend fun getGroupByThreadId(threadId: Long): Group?

    // Group Members
    @Insert
    suspend fun insertGroupMember(member: GroupMember)

    @Delete
    suspend fun deleteGroupMember(member: GroupMember)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getGroupMembers(groupId: Long): List<GroupMember>

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteAllGroupMembers(groupId: Long)

    // Combined queries
    @Transaction
    @Query("SELECT * FROM groups WHERE id = :groupId")
    suspend fun getGroupWithMembers(groupId: Long): GroupWithMembers?

    @Transaction
    @Query("SELECT * FROM groups ORDER BY lastMessageAt DESC")
    fun getAllGroupsWithMembers(): Flow<List<GroupWithMembers>>
}

// Data class for group with its members
data class GroupWithMembers(
    @Embedded val group: Group,
    @Relation(
        parentColumn = "id",
        entityColumn = "groupId"
    )
    val members: List<GroupMember>
)
