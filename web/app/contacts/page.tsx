'use client'

import { useRouter } from 'next/navigation'
import ContactsList from '@/components/ContactsList'
import { ContactsListSkeleton } from '@/components/SkeletonLoaders'
import { useAuth } from '@/lib/hooks/useAuth'

export default function ContactsPage() {
  const router = useRouter()
  const { userId, isLoading } = useAuth()

  const handleSelectContact = (phoneNumber: string, contactName: string) => {
    // Navigate to messages with this contact
    router.push(`/messages?address=${encodeURIComponent(phoneNumber)}&name=${encodeURIComponent(contactName)}`)
  }

  if (isLoading) {
    return (
      <div className="min-h-screen bg-mesh">
        <ContactsListSkeleton />
      </div>
    )
  }

  if (!userId) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-mesh">
        <p className="text-gray-500 dark:text-gray-400">Please pair your device first.</p>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-mesh">
      {/* Navigation Header */}
      <nav className="glass-panel m-3 rounded-2xl">
        <div className="max-w-7xl mx-auto px-5">
          <div className="flex items-center justify-between h-14">
            <div className="flex items-center gap-6">
              <h1 className="text-xl font-bold text-gradient">SyncFlow</h1>
              <div className="flex items-center gap-1">
                <a
                  href="/messages"
                  className="px-3 py-1.5 rounded-lg text-sm text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white hover:bg-white/50 dark:hover:bg-white/5 transition-all"
                >
                  Messages
                </a>
                <a
                  href="/contacts"
                  className="px-3 py-1.5 rounded-lg text-sm text-blue-600 font-medium bg-blue-500/10"
                >
                  Contacts
                </a>
              </div>
            </div>
          </div>
        </div>
      </nav>

      {/* Contacts List */}
      <main className="max-w-4xl mx-auto" style={{ height: 'calc(100vh - 5rem)' }}>
        <ContactsList userId={userId} onSelectContact={handleSelectContact} />
      </main>
    </div>
  )
}
