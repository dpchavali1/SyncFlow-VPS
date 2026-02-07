'use client'

import React, { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import vpsService from '@/lib/vps'

interface AdminSession {
  authenticated: boolean
  timestamp: number
  expiresAt: number
}

interface SyncGroup {
  syncGroupId: string
  plan: string
  deviceCount: number
  deviceLimit: number
  createdAt: number
  masterDevice: string
}

const isValidAdminSession = (): boolean => {
  try {
    const sessionStr = localStorage.getItem('syncflow_admin_session')
    if (!sessionStr) return false
    const session: AdminSession = JSON.parse(sessionStr)
    if (!session.authenticated) return false
    if (Date.now() > session.expiresAt) {
      localStorage.removeItem('syncflow_admin_session')
      return false
    }
    return true
  } catch {
    return false
  }
}

export default function SyncGroupsPage() {
  const router = useRouter()
  const [groups, setGroups] = useState<SyncGroup[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState<'all' | 'free' | 'premium'>('all')

  useEffect(() => {
    if (!isValidAdminSession()) {
      router.push('/admin/login')
      return
    }
    loadSyncGroups()
  }, [router])

  const loadSyncGroups = async () => {
    try {
      setIsLoading(true)
      setError('')
      const data = await vpsService.getAdminSyncGroups()

      if (data.success) {
        setGroups(data.groups)
      } else {
        setError('Failed to load sync groups')
      }
    } catch (err) {
      console.error('Error loading sync groups:', err)
      setError('An error occurred while loading sync groups')
    } finally {
      setIsLoading(false)
    }
  }

  const filteredGroups = groups.filter((g) => {
    if (filter === 'free') return g.plan === 'free'
    if (filter === 'premium') return g.plan !== 'free'
    return true
  })

  const premiumCount = groups.filter((g) => g.plan !== 'free').length
  const freeCount = groups.filter((g) => g.plan === 'free').length
  const totalDevices = groups.reduce((sum, g) => sum + g.deviceCount, 0)

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex justify-between items-center">
        <h1 className="text-3xl font-bold">Sync Groups</h1>
        <button
          onClick={loadSyncGroups}
          className="px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
          disabled={isLoading}
        >
          {isLoading ? 'Loading...' : 'Refresh'}
        </button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-4 gap-4">
        <StatCard label="Total Groups" value={groups.length.toString()} />
        <StatCard label="Free Tier" value={freeCount.toString()} color="gray" />
        <StatCard label="Premium Tier" value={premiumCount.toString()} color="green" />
        <StatCard label="Total Devices" value={totalDevices.toString()} color="blue" />
      </div>

      {/* Filters */}
      <div className="flex gap-2">
        {(['all', 'free', 'premium'] as const).map((f) => (
          <button
            key={f}
            onClick={() => setFilter(f)}
            className={`px-4 py-2 rounded capitalize ${
              filter === f
                ? 'bg-blue-500 text-white'
                : 'bg-gray-200 text-gray-800 hover:bg-gray-300'
            }`}
          >
            {f}
          </button>
        ))}
      </div>

      {/* Error */}
      {error && (
        <div className="p-4 bg-red-50 border border-red-200 rounded text-red-800">
          {error}
        </div>
      )}

      {/* Groups Table */}
      <div className="overflow-x-auto border rounded-lg">
        <table className="w-full">
          <thead className="bg-gray-100 border-b">
            <tr>
              <th className="px-4 py-3 text-left text-sm font-semibold">Sync Group ID</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Plan</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Devices</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Created</th>
              <th className="px-4 py-3 text-left text-sm font-semibold">Actions</th>
            </tr>
          </thead>
          <tbody>
            {filteredGroups.map((group) => (
              <tr key={group.syncGroupId} className="border-b hover:bg-gray-50">
                <td className="px-4 py-3 text-sm font-mono">{group.syncGroupId.slice(0, 20)}...</td>
                <td className="px-4 py-3 text-sm">
                  <span
                    className={`px-2 py-1 rounded text-xs font-semibold ${
                      group.plan === 'free'
                        ? 'bg-gray-100 text-gray-800'
                        : 'bg-green-100 text-green-800'
                    }`}
                  >
                    {group.plan}
                  </span>
                </td>
                <td className="px-4 py-3 text-sm">
                  {group.deviceCount}/{group.deviceLimit}
                </td>
                <td className="px-4 py-3 text-sm">
                  {new Date(group.createdAt).toLocaleDateString()}
                </td>
                <td className="px-4 py-3 text-sm space-x-2">
                  <Link
                    href={`/admin/sync-groups/${group.syncGroupId}`}
                    className="text-blue-500 hover:underline"
                  >
                    View
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {filteredGroups.length === 0 && !isLoading && (
        <div className="text-center py-8 text-gray-500">
          No sync groups found
        </div>
      )}
    </div>
  )
}

function StatCard(
  {
    label,
    value,
    color = 'blue'
  }: {
    label: string
    value: string
    color?: string
  }
) {
  const bgColor = {
    blue: 'bg-blue-50',
    green: 'bg-green-50',
    gray: 'bg-gray-50'
  }[color]

  const textColor = {
    blue: 'text-blue-600',
    green: 'text-green-600',
    gray: 'text-gray-600'
  }[color]

  return (
    <div className={`${bgColor} p-4 rounded-lg`}>
      <p className="text-sm text-gray-600">{label}</p>
      <p className={`text-2xl font-bold ${textColor}`}>{value}</p>
    </div>
  )
}
