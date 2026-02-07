'use client'

import { useState, useEffect } from 'react'
import {
  Contact,
  listenToContactsOptimized,
  createContact,
  updateContact,
  deleteContact,
} from '@/lib/firebase'

interface ContactsListProps {
  userId: string
  onSelectContact?: (phoneNumber: string, contactName: string) => void
}

const PHONE_TYPES = ['Mobile', 'Home', 'Work', 'Main', 'Other']

export default function ContactsList({ userId, onSelectContact }: ContactsListProps) {
  const [contacts, setContacts] = useState<Contact[]>([])
  const [searchQuery, setSearchQuery] = useState('')
  const [isLoading, setIsLoading] = useState(true)
  const [showCreateModal, setShowCreateModal] = useState(false)
  const [editingContact, setEditingContact] = useState<Contact | null>(null)
  const [deleteConfirmContact, setDeleteConfirmContact] = useState<Contact | null>(null)

  // Form state
  const [formName, setFormName] = useState('')
  const [formPhone, setFormPhone] = useState('')
  const [formPhoneType, setFormPhoneType] = useState('Mobile')
  const [formEmail, setFormEmail] = useState('')
  const [formNotes, setFormNotes] = useState('')
  const [isSaving, setIsSaving] = useState(false)

  useEffect(() => {
    if (!userId) return

    // Optimized contacts listener - uses child events instead of full snapshot
    const unsubContacts = listenToContactsOptimized(userId, (androidContacts) => {
      setContacts(androidContacts)
      setIsLoading(false)
    })

    return () => {
      unsubContacts()
    }
  }, [userId])

  const matchesSearch = (contact: Contact) => {
    const query = searchQuery.toLowerCase()
    if (!query) return true
    if (contact.displayName.toLowerCase().includes(query)) return true
    const phone = contact.phoneNumber ?? ''
    if (phone.toLowerCase().includes(query)) return true
    const normalized = contact.normalizedNumber ?? phone
    if (normalized.includes(query)) return true
    const digits = query.replace(/\D/g, '')
    if (!digits) return false
    const numberDigits = (normalized || phone).replace(/\D/g, '')
    return numberDigits.includes(digits)
  }

  const filteredPendingContacts = contacts.filter(
    (c) => c.sync.pendingAndroidSync && matchesSearch(c)
  )

  const filteredSyncedContacts = contacts.filter(
    (c) => !c.sync.pendingAndroidSync && matchesSearch(c)
  )

  const getInitials = (name: string) => {
    const parts = name.split(' ')
    if (parts.length >= 2) {
      return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
    }
    return name.slice(0, 2).toUpperCase()
  }

  const formatPhoneNumber = (phone: string) => {
    const digits = phone.replace(/\D/g, '')
    if (digits.length === 10) {
      return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`
    }
    return phone
  }

  const resetForm = () => {
    setFormName('')
    setFormPhone('')
    setFormPhoneType('Mobile')
    setFormEmail('')
    setFormNotes('')
  }

  const openCreateModal = () => {
    resetForm()
    setEditingContact(null)
    setShowCreateModal(true)
  }

  const openEditModal = (contact: Contact) => {
    setFormName(contact.displayName)
    setFormPhone(contact.phoneNumber ?? '')
    setFormPhoneType(contact.phoneType)
    setFormEmail(contact.email || '')
    setFormNotes(contact.notes || '')
    setEditingContact(contact)
    setShowCreateModal(true)
  }

  const handleSave = async () => {
    if (!formName.trim() || !formPhone.trim()) return

    setIsSaving(true)
    try {
      if (editingContact) {
        await updateContact(
          userId,
          editingContact.id,
          formName.trim(),
          formPhone.trim(),
          formPhoneType,
          formEmail.trim() || undefined,
          formNotes.trim() || undefined
        )
      } else {
        await createContact(
          userId,
          formName.trim(),
          formPhone.trim(),
          formPhoneType,
          formEmail.trim() || undefined,
          formNotes.trim() || undefined
        )
      }
      setShowCreateModal(false)
      resetForm()
    } catch (error) {
      console.error('Error saving contact:', error)
      alert('Failed to save contact')
    } finally {
      setIsSaving(false)
    }
  }

  const handleDelete = async (contact: Contact) => {
    try {
      await deleteContact(userId, contact.id)
      setDeleteConfirmContact(null)
    } catch (error) {
      console.error('Error deleting contact:', error)
      alert('Failed to delete contact')
    }
  }

  return (
    <div className="flex flex-col h-full bg-gray-50 dark:bg-gray-900">
      {/* Header */}
      <div className="p-4 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800">
        <div className="flex items-center gap-4">
          {/* Search */}
          <div className="flex-1 relative">
            <input
              type="text"
              placeholder="Search contacts..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-10 pr-4 py-2 rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
            <svg
              className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
              />
            </svg>
          </div>

          {/* New Contact Button */}
          <button
            onClick={openCreateModal}
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg font-medium transition-colors"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 4v16m8-8H4"
              />
            </svg>
            New Contact
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        {isLoading ? (
          <div className="flex items-center justify-center h-full">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
          </div>
        ) : filteredSyncedContacts.length === 0 && filteredPendingContacts.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-gray-500 dark:text-gray-400">
            <svg className="w-16 h-16 mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"
              />
            </svg>
            <p className="text-lg font-medium mb-2">
              {searchQuery ? 'No contacts found' : 'No contacts yet'}
            </p>
            <p className="text-sm text-center max-w-xs">
              {searchQuery
                ? 'Try a different search term'
                : 'Contacts from your Android phone will appear here. You can also create new contacts.'}
            </p>
            {!searchQuery && (
              <button
                onClick={openCreateModal}
                className="mt-4 flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg font-medium transition-colors"
              >
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 4v16m8-8H4"
                  />
                </svg>
                Create New Contact
              </button>
            )}
          </div>
        ) : (
          <div className="divide-y divide-gray-200 dark:divide-gray-700">
            {filteredPendingContacts.length > 0 && (
              <div className="px-4 py-2 bg-blue-50 dark:bg-blue-900/20 sticky top-0 z-10">
                <div className="flex items-center gap-2">
                  <svg className="w-4 h-4 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                  </svg>
                  <span className="text-sm font-medium text-blue-700 dark:text-blue-300">
                    Pending web edits ({filteredPendingContacts.length})
                  </span>
                </div>
              </div>
            )}
            {filteredPendingContacts.map((contact) => (
              <div
                key={contact.id}
                className="flex items-center gap-4 p-4 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors group"
              >
                <div className="w-12 h-12 rounded-full bg-blue-100 dark:bg-blue-900 flex items-center justify-center">
                  <span className="text-blue-600 dark:text-blue-300 font-semibold">
                    {getInitials(contact.displayName)}
                  </span>
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <p className="font-medium text-gray-900 dark:text-white truncate">
                      {contact.displayName}
                    </p>
                    <span className="text-xs text-orange-500">Pending</span>
                  </div>
                  <p className="text-sm text-gray-500 dark:text-gray-400">
                    {formatPhoneNumber(contact.phoneNumber || '')} • {contact.phoneType}
                  </p>
                </div>
                <div className="flex items-center gap-2 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button
                    onClick={() => openEditModal(contact)}
                    className="p-2 text-gray-500 hover:text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
                  >
                    Edit
                  </button>
                  <button
                    onClick={() => setDeleteConfirmContact(contact)}
                    className="p-2 text-gray-500 hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors"
                  >
                    Delete
                  </button>
                </div>
              </div>
            ))}
            {filteredSyncedContacts.map((contact) => (
              <div
                key={contact.id}
                className="flex items-center gap-4 p-4 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors group cursor-pointer"
                onClick={() => onSelectContact?.(contact.phoneNumber || '', contact.displayName)}
              >
                <div className="w-12 h-12 rounded-full bg-green-100 dark:bg-green-900 flex items-center justify-center overflow-hidden">
                  {contact.photo?.thumbnailBase64 ? (
                    <img
                      src={`data:image/jpeg;base64,${contact.photo.thumbnailBase64}`}
                      alt={contact.displayName}
                      className="w-full h-full object-cover"
                    />
                  ) : (
                    <span className="text-green-600 dark:text-green-300 font-semibold">
                      {getInitials(contact.displayName)}
                    </span>
                  )}
                </div>
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-gray-900 dark:text-white truncate">
                    {contact.displayName}
                  </p>
                  <p className="text-sm text-gray-500 dark:text-gray-400">
                    {formatPhoneNumber(contact.phoneNumber || '')} • {contact.phoneType}
                  </p>
                </div>
                {onSelectContact && (
                  <button
                    onClick={(e) => {
                      e.stopPropagation()
                      onSelectContact(contact.phoneNumber || '', contact.displayName)
                    }}
                    className="p-2 text-gray-500 hover:text-green-600 hover:bg-green-50 rounded-lg transition-colors opacity-0 group-hover:opacity-100"
                  >
                    Message
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Create/Edit Modal */}
      {showCreateModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white dark:bg-gray-800 rounded-xl shadow-xl w-full max-w-md mx-4">
            <div className="p-4 border-b border-gray-200 dark:border-gray-700">
              <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
                {editingContact ? 'Edit Contact' : 'New Contact'}
              </h2>
            </div>

            <div className="p-4 space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  Name *
                </label>
                <input
                  type="text"
                  value={formName}
                  onChange={(e) => setFormName(e.target.value)}
                  placeholder="John Doe"
                  className="w-full px-3 py-2 rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  Phone Number *
                </label>
                <input
                  type="tel"
                  value={formPhone}
                  onChange={(e) => setFormPhone(e.target.value)}
                  placeholder="+1 (555) 123-4567"
                  className="w-full px-3 py-2 rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  Phone Type
                </label>
                <select
                  value={formPhoneType}
                  onChange={(e) => setFormPhoneType(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                >
                  {PHONE_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {type}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  Email (optional)
                </label>
                <input
                  type="email"
                  value={formEmail}
                  onChange={(e) => setFormEmail(e.target.value)}
                  placeholder="john@example.com"
                  className="w-full px-3 py-2 rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  Notes (optional)
                </label>
                <textarea
                  value={formNotes}
                  onChange={(e) => setFormNotes(e.target.value)}
                  placeholder="Any notes about this contact..."
                  rows={2}
                  className="w-full px-3 py-2 rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent resize-none"
                />
              </div>

              <div className="flex items-center gap-2 p-3 bg-blue-50 dark:bg-blue-900/20 rounded-lg">
                <svg className="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                <p className="text-sm text-blue-700 dark:text-blue-300">
                  This contact will automatically sync to your Android phone.
                </p>
              </div>
            </div>

            <div className="p-4 border-t border-gray-200 dark:border-gray-700 flex justify-end gap-3">
              <button
                onClick={() => {
                  setShowCreateModal(false)
                  resetForm()
                }}
                className="px-4 py-2 text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleSave}
                disabled={!formName.trim() || !formPhone.trim() || isSaving}
                className="px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400 text-white rounded-lg font-medium transition-colors"
              >
                {isSaving ? 'Saving...' : 'Save'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Delete Confirmation Modal */}
      {deleteConfirmContact && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white dark:bg-gray-800 rounded-xl shadow-xl w-full max-w-sm mx-4 p-6">
            <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-2">
              Delete Contact
            </h3>
            <p className="text-gray-600 dark:text-gray-400 mb-6">
              Are you sure you want to delete &quot;{deleteConfirmContact.displayName}&quot;? This will also remove it from your Android phone.
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setDeleteConfirmContact(null)}
                className="px-4 py-2 text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={() => handleDelete(deleteConfirmContact)}
                className="px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-lg font-medium transition-colors"
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
