'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { ArrowLeft, Users, Smartphone, MessageSquare, RefreshCw, Trash2, ChevronRight } from 'lucide-react'
import vpsService from '@/lib/vps'

interface VPSUser {
  id: string
  createdAt: string
  updatedAt: string
  deviceCount: number
  messageCount: number
}

interface VPSStats {
  totalUsers: number
  totalDevices: number
  totalMessages: number
  totalContacts: number
  totalCalls: number
}

export default function AdminUsersPage() {
  const router = useRouter()
  const [users, setUsers] = useState<VPSUser[]>([])
  const [stats, setStats] = useState<VPSStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const limit = 20

  useEffect(() => {
    loadData()
  }, [page])

  const loadData = async () => {
    setLoading(true)
    setError(null)

    try {
      // Check VPS authentication
      if (!vpsService.isAuthenticated) {
        setError('Not authenticated with VPS. Please log in as admin.')
        setLoading(false)
        return
      }

      // Load stats and users in parallel
      const [statsData, usersData] = await Promise.all([
        vpsService.getAdminStats(),
        vpsService.getAdminUsers({ limit, offset: page * limit }),
      ])

      setStats(statsData)
      setUsers(usersData.users)
      setTotal(usersData.total)
    } catch (err: any) {
      console.error('Failed to load VPS admin data:', err)
      setError(err.message || 'Failed to load data from VPS')
    } finally {
      setLoading(false)
    }
  }

  const handleDeleteUser = async (userId: string) => {
    if (!confirm(`Are you sure you want to delete user ${userId}? This will delete all their data.`)) {
      return
    }

    try {
      await vpsService.deleteAdminUser(userId)
      // Reload data
      loadData()
    } catch (err: any) {
      alert('Failed to delete user: ' + err.message)
    }
  }

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleString()
  }

  if (loading) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center">
          <RefreshCw className="w-8 h-8 animate-spin text-blue-500 mx-auto mb-4" />
          <p className="text-gray-600">Loading VPS data...</p>
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="min-h-screen bg-gray-50 p-8">
        <div className="max-w-4xl mx-auto">
          <div className="bg-red-50 border border-red-200 rounded-lg p-6">
            <h2 className="text-red-800 font-semibold mb-2">Error Loading VPS Data</h2>
            <p className="text-red-600">{error}</p>
            <button
              onClick={loadData}
              className="mt-4 px-4 py-2 bg-red-100 text-red-700 rounded hover:bg-red-200"
            >
              Retry
            </button>
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
          <Link href="/admin" className="p-2 hover:bg-gray-200 rounded-lg">
            <ArrowLeft className="w-5 h-5" />
          </Link>
          <div>
            <h1 className="text-2xl font-bold text-gray-900">VPS Users</h1>
            <p className="text-gray-600">Viewing data from VPS PostgreSQL database</p>
          </div>
          <button
            onClick={loadData}
            className="ml-auto p-2 hover:bg-gray-200 rounded-lg"
            title="Refresh"
          >
            <RefreshCw className="w-5 h-5" />
          </button>
        </div>

        {/* Stats */}
        {stats && (
          <div className="grid grid-cols-2 md:grid-cols-5 gap-4 mb-8">
            <StatCard label="Total Users" value={stats.totalUsers} icon={<Users className="w-5 h-5" />} />
            <StatCard label="Total Devices" value={stats.totalDevices} icon={<Smartphone className="w-5 h-5" />} />
            <StatCard label="Total Messages" value={stats.totalMessages} icon={<MessageSquare className="w-5 h-5" />} />
            <StatCard label="Total Contacts" value={stats.totalContacts} icon={<Users className="w-5 h-5" />} />
            <StatCard label="Total Calls" value={stats.totalCalls} icon={<Smartphone className="w-5 h-5" />} />
          </div>
        )}

        {/* Users Table */}
        <div className="bg-white rounded-lg shadow overflow-hidden">
          <div className="p-4 border-b bg-gray-50">
            <h2 className="font-semibold text-gray-900">Users ({total})</h2>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-gray-50 text-left text-sm text-gray-600">
                <tr>
                  <th className="px-4 py-3">User ID</th>
                  <th className="px-4 py-3">Created</th>
                  <th className="px-4 py-3">Devices</th>
                  <th className="px-4 py-3">Messages</th>
                  <th className="px-4 py-3">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {users.map((user) => (
                  <tr key={user.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3">
                      <code className="text-sm bg-gray-100 px-2 py-1 rounded">
                        {user.id.substring(0, 12)}...
                      </code>
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-600">
                      {formatDate(user.createdAt)}
                    </td>
                    <td className="px-4 py-3">
                      <span className="inline-flex items-center px-2 py-1 bg-blue-100 text-blue-700 rounded text-sm">
                        {user.deviceCount} devices
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="inline-flex items-center px-2 py-1 bg-green-100 text-green-700 rounded text-sm">
                        {user.messageCount} messages
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <Link
                          href={`/admin/users/${user.id}`}
                          className="p-2 hover:bg-gray-200 rounded text-gray-600"
                          title="View Details"
                        >
                          <ChevronRight className="w-4 h-4" />
                        </Link>
                        <button
                          onClick={() => handleDeleteUser(user.id)}
                          className="p-2 hover:bg-red-100 rounded text-red-600"
                          title="Delete User"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          {total > limit && (
            <div className="p-4 border-t bg-gray-50 flex items-center justify-between">
              <span className="text-sm text-gray-600">
                Showing {page * limit + 1} - {Math.min((page + 1) * limit, total)} of {total}
              </span>
              <div className="flex gap-2">
                <button
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                  disabled={page === 0}
                  className="px-3 py-1 border rounded hover:bg-gray-100 disabled:opacity-50"
                >
                  Previous
                </button>
                <button
                  onClick={() => setPage(p => p + 1)}
                  disabled={(page + 1) * limit >= total}
                  className="px-3 py-1 border rounded hover:bg-gray-100 disabled:opacity-50"
                >
                  Next
                </button>
              </div>
            </div>
          )}

          {users.length === 0 && (
            <div className="p-8 text-center text-gray-500">
              No users found in VPS database
            </div>
          )}
        </div>

        {/* VPS Server Info */}
        <div className="mt-8 p-4 bg-blue-50 rounded-lg">
          <p className="text-sm text-blue-700">
            <strong>VPS Server:</strong> {process.env.NEXT_PUBLIC_VPS_URL || 'http://5.78.188.206'}
          </p>
          <p className="text-sm text-blue-600 mt-1">
            Data is read directly from the PostgreSQL database on the VPS server.
          </p>
        </div>
      </div>
    </div>
  )
}

function StatCard({ label, value, icon }: { label: string; value: number; icon: React.ReactNode }) {
  return (
    <div className="bg-white rounded-lg shadow p-4">
      <div className="flex items-center gap-3">
        <div className="p-2 bg-blue-100 rounded-lg text-blue-600">
          {icon}
        </div>
        <div>
          <p className="text-2xl font-bold text-gray-900">{value.toLocaleString()}</p>
          <p className="text-sm text-gray-600">{label}</p>
        </div>
      </div>
    </div>
  )
}
