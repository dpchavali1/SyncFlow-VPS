'use client'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, Smartphone, MessageSquare, Users, RefreshCw, Trash2 } from 'lucide-react'
import vpsService from '@/lib/vps'

interface UserDetails {
  id: string
  createdAt: string
  updatedAt: string
  devices: Array<{
    id: string
    type: string
    name: string
    createdAt: string
    lastSeen: string
  }>
  messageCount: number
  contactCount: number
}

interface Message {
  id: string
  address: string
  body: string
  date: string
  type: number
  read: boolean
  isMms: boolean
}

export default function AdminUserDetailPage() {
  const params = useParams()
  const router = useRouter()
  const userId = params.userId as string

  const [user, setUser] = useState<UserDetails | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [messagesTotal, setMessagesTotal] = useState(0)
  const [messagesPage, setMessagesPage] = useState(0)
  const messagesLimit = 20

  useEffect(() => {
    loadUserData()
  }, [userId])

  useEffect(() => {
    loadMessages()
  }, [userId, messagesPage])

  const loadUserData = async () => {
    setLoading(true)
    setError(null)

    try {
      if (!vpsService.isAuthenticated) {
        setError('Not authenticated with VPS. Please log in as admin.')
        setLoading(false)
        return
      }

      const userData = await vpsService.getAdminUserDetails(userId)
      setUser(userData)
    } catch (err: any) {
      console.error('Failed to load user:', err)
      setError(err.message || 'Failed to load user data')
    } finally {
      setLoading(false)
    }
  }

  const loadMessages = async () => {
    try {
      const data = await vpsService.getAdminUserMessages(userId, {
        limit: messagesLimit,
        offset: messagesPage * messagesLimit,
      })
      setMessages(data.messages)
      setMessagesTotal(data.total)
    } catch (err: any) {
      console.error('Failed to load messages:', err)
    }
  }

  const handleDeleteUser = async () => {
    if (!confirm(`Are you sure you want to delete user ${userId}? This will delete ALL their data permanently.`)) {
      return
    }

    try {
      await vpsService.deleteAdminUser(userId)
      router.push('/admin/users')
    } catch (err: any) {
      alert('Failed to delete user: ' + err.message)
    }
  }

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleString()
  }

  const getMessageTypeLabel = (type: number) => {
    switch (type) {
      case 1: return 'Received'
      case 2: return 'Sent'
      default: return `Type ${type}`
    }
  }

  if (loading) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <RefreshCw className="w-8 h-8 animate-spin text-blue-500" />
      </div>
    )
  }

  if (error || !user) {
    return (
      <div className="min-h-screen bg-gray-50 p-8">
        <div className="max-w-4xl mx-auto">
          <div className="bg-red-50 border border-red-200 rounded-lg p-6">
            <h2 className="text-red-800 font-semibold mb-2">Error</h2>
            <p className="text-red-600">{error || 'User not found'}</p>
            <Link href="/admin/users" className="mt-4 inline-block text-blue-500 hover:underline">
              Back to Users
            </Link>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gray-50 p-8">
      <div className="max-w-6xl mx-auto">
        {/* Header */}
        <div className="flex items-center gap-4 mb-8">
          <Link href="/admin/users" className="p-2 hover:bg-gray-200 rounded-lg">
            <ArrowLeft className="w-5 h-5" />
          </Link>
          <div className="flex-1">
            <h1 className="text-2xl font-bold text-gray-900">User Details</h1>
            <code className="text-sm bg-gray-200 px-2 py-1 rounded">{user.id}</code>
          </div>
          <button
            onClick={handleDeleteUser}
            className="flex items-center gap-2 px-4 py-2 bg-red-100 text-red-700 rounded-lg hover:bg-red-200"
          >
            <Trash2 className="w-4 h-4" />
            Delete User
          </button>
        </div>

        {/* User Info */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
          <div className="bg-white rounded-lg shadow p-6">
            <div className="flex items-center gap-3 mb-4">
              <div className="p-2 bg-blue-100 rounded-lg text-blue-600">
                <Users className="w-5 h-5" />
              </div>
              <h2 className="font-semibold">User Info</h2>
            </div>
            <dl className="space-y-2 text-sm">
              <div>
                <dt className="text-gray-500">Created</dt>
                <dd className="text-gray-900">{formatDate(user.createdAt)}</dd>
              </div>
              <div>
                <dt className="text-gray-500">Updated</dt>
                <dd className="text-gray-900">{formatDate(user.updatedAt)}</dd>
              </div>
            </dl>
          </div>

          <div className="bg-white rounded-lg shadow p-6">
            <div className="flex items-center gap-3 mb-4">
              <div className="p-2 bg-green-100 rounded-lg text-green-600">
                <MessageSquare className="w-5 h-5" />
              </div>
              <h2 className="font-semibold">Messages</h2>
            </div>
            <p className="text-3xl font-bold text-gray-900">{user.messageCount.toLocaleString()}</p>
          </div>

          <div className="bg-white rounded-lg shadow p-6">
            <div className="flex items-center gap-3 mb-4">
              <div className="p-2 bg-purple-100 rounded-lg text-purple-600">
                <Users className="w-5 h-5" />
              </div>
              <h2 className="font-semibold">Contacts</h2>
            </div>
            <p className="text-3xl font-bold text-gray-900">{user.contactCount.toLocaleString()}</p>
          </div>
        </div>

        {/* Devices */}
        <div className="bg-white rounded-lg shadow mb-8">
          <div className="p-4 border-b bg-gray-50">
            <h2 className="font-semibold flex items-center gap-2">
              <Smartphone className="w-5 h-5" />
              Devices ({user.devices.length})
            </h2>
          </div>
          <div className="divide-y">
            {user.devices.map((device) => (
              <div key={device.id} className="p-4 flex items-center justify-between">
                <div>
                  <p className="font-medium text-gray-900">{device.name || 'Unknown Device'}</p>
                  <p className="text-sm text-gray-600">
                    {device.type} &bull; {device.id.substring(0, 8)}...
                  </p>
                </div>
                <div className="text-right text-sm text-gray-500">
                  <p>Last seen: {formatDate(device.lastSeen)}</p>
                  <p>Created: {formatDate(device.createdAt)}</p>
                </div>
              </div>
            ))}
            {user.devices.length === 0 && (
              <div className="p-8 text-center text-gray-500">No devices</div>
            )}
          </div>
        </div>

        {/* Recent Messages */}
        <div className="bg-white rounded-lg shadow">
          <div className="p-4 border-b bg-gray-50">
            <h2 className="font-semibold flex items-center gap-2">
              <MessageSquare className="w-5 h-5" />
              Recent Messages ({messagesTotal})
            </h2>
          </div>
          <div className="divide-y">
            {messages.map((msg) => (
              <div key={msg.id} className="p-4">
                <div className="flex items-center justify-between mb-2">
                  <span className="font-medium text-gray-900">{msg.address}</span>
                  <span className={`text-xs px-2 py-1 rounded ${
                    msg.type === 1 ? 'bg-blue-100 text-blue-700' : 'bg-green-100 text-green-700'
                  }`}>
                    {getMessageTypeLabel(msg.type)}
                  </span>
                </div>
                <p className="text-sm text-gray-600 line-clamp-2">{msg.body}</p>
                <p className="text-xs text-gray-400 mt-1">{formatDate(msg.date)}</p>
              </div>
            ))}
            {messages.length === 0 && (
              <div className="p-8 text-center text-gray-500">No messages</div>
            )}
          </div>

          {/* Pagination */}
          {messagesTotal > messagesLimit && (
            <div className="p-4 border-t bg-gray-50 flex items-center justify-between">
              <span className="text-sm text-gray-600">
                Page {messagesPage + 1} of {Math.ceil(messagesTotal / messagesLimit)}
              </span>
              <div className="flex gap-2">
                <button
                  onClick={() => setMessagesPage(p => Math.max(0, p - 1))}
                  disabled={messagesPage === 0}
                  className="px-3 py-1 border rounded hover:bg-gray-100 disabled:opacity-50"
                >
                  Previous
                </button>
                <button
                  onClick={() => setMessagesPage(p => p + 1)}
                  disabled={(messagesPage + 1) * messagesLimit >= messagesTotal}
                  className="px-3 py-1 border rounded hover:bg-gray-100 disabled:opacity-50"
                >
                  Next
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
