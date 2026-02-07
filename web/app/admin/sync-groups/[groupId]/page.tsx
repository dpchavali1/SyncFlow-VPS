'use client'

import React, { useEffect, useState } from 'react'
import { httpsCallable, getFunctions } from 'firebase/functions'
import { useParams, useRouter } from 'next/navigation'

interface DeviceInfo {
  deviceId: string
  deviceType: string
  joinedAt: number
  lastSyncedAt?: number
  status: string
  deviceName?: string
}

interface HistoryEntry {
  timestamp: number
  action: string
  deviceId?: string
  newPlan?: string
  previousPlan?: string
}

interface GroupDetails {
  syncGroupId: string
  plan: string
  deviceLimit: number
  deviceCount: number
  createdAt: number
  masterDevice: string
  wasPremium: boolean
  firstPremiumDate?: number
  devices: DeviceInfo[]
  history: HistoryEntry[]
}

export default function GroupDetailsPage() {
  const params = useParams()
  const router = useRouter()
  const syncGroupId = params.groupId as string

  const [group, setGroup] = useState<GroupDetails | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [selectedPlan, setSelectedPlan] = useState('monthly')

  useEffect(() => {
    loadGroupDetails()
  }, [])

  const loadGroupDetails = async () => {
    try {
      setIsLoading(true)
      const functions = getFunctions()
      const getDetails = httpsCallable(functions, 'getSyncGroupDetails')
      const result = await getDetails({ syncGroupId })
      const data = result.data as { success: boolean; data: GroupDetails }

      if (data.success) {
        setGroup(data.data)
      } else {
        setError('Failed to load group details')
      }
    } catch (err) {
      console.error('Error loading group details:', err)
      setError('An error occurred while loading group details')
    } finally {
      setIsLoading(false)
    }
  }

  const handleUpgradePlan = async () => {
    if (!group) return

    try {
      const functions = getFunctions()
      const updatePlan = httpsCallable(functions, 'updateSyncGroupPlan')
      const result = await updatePlan({ syncGroupId, plan: selectedPlan })
      const data = result.data as { success: boolean }

      if (data.success) {
        loadGroupDetails()
        alert(`Upgraded to ${selectedPlan} plan`)
      } else {
        alert('Failed to upgrade')
      }
    } catch (err) {
      console.error('Upgrade error:', err)
      alert('Error upgrading plan')
    }
  }

  const handleRemoveDevice = async (deviceId: string) => {
    if (!confirm('Remove this device from the group?')) return

    try {
      const functions = getFunctions()
      const removeDevice = httpsCallable(functions, 'removeDeviceFromSyncGroup')
      const result = await removeDevice({ syncGroupId, deviceId })
      const data = result.data as { success: boolean }

      if (data.success) {
        loadGroupDetails()
        alert('Device removed')
      } else {
        alert('Failed to remove device')
      }
    } catch (err) {
      console.error('Remove error:', err)
      alert('Error removing device')
    }
  }

  if (isLoading) {
    return <div>Loading...</div>
  }

  if (!group) {
    return <div>Group not found</div>
  }

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center gap-4">
        <button
          onClick={() => router.back()}
          className="text-blue-500 hover:underline"
        >
          ← Back
        </button>
        <h1 className="text-3xl font-bold">Sync Group Details</h1>
      </div>

      {/* Group Info */}
      <div className="bg-white p-6 rounded-lg border">
        <div className="grid grid-cols-2 gap-4 mb-4">
          <div>
            <p className="text-sm text-gray-600">Sync Group ID</p>
            <p className="font-mono text-sm">{group.syncGroupId}</p>
          </div>
          <div>
            <p className="text-sm text-gray-600">Created</p>
            <p>{new Date(group.createdAt).toLocaleString()}</p>
          </div>
          <div>
            <p className="text-sm text-gray-600">Plan</p>
            <span
              className={`px-2 py-1 rounded text-xs font-semibold ${
                group.plan === 'free'
                  ? 'bg-gray-100 text-gray-800'
                  : 'bg-green-100 text-green-800'
              }`}
            >
              {group.plan}
            </span>
          </div>
          <div>
            <p className="text-sm text-gray-600">Devices</p>
            <p>{group.deviceCount}/{group.deviceLimit}</p>
          </div>
        </div>

        {group.plan === 'free' && (
          <div className="flex gap-2 items-end">
            <div>
              <label className="text-sm text-gray-600">Upgrade to:</label>
              <select
                value={selectedPlan}
                onChange={(e) => setSelectedPlan(e.target.value)}
                className="mt-1 px-2 py-1 border rounded"
              >
                <option value="monthly">Monthly ($3.99/mo)</option>
                <option value="yearly">Yearly ($29.99/yr)</option>
                <option value="lifetime">Lifetime ($99.99)</option>
              </select>
            </div>
            <button
              onClick={handleUpgradePlan}
              className="px-4 py-2 bg-green-500 text-white rounded hover:bg-green-600"
            >
              Upgrade
            </button>
          </div>
        )}
      </div>

      {/* Devices */}
      <div className="bg-white p-6 rounded-lg border">
        <h2 className="text-xl font-bold mb-4">Devices ({group.deviceCount})</h2>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-gray-100">
              <tr>
                <th className="px-4 py-2 text-left text-sm font-semibold">Device ID</th>
                <th className="px-4 py-2 text-left text-sm font-semibold">Type</th>
                <th className="px-4 py-2 text-left text-sm font-semibold">Joined</th>
                <th className="px-4 py-2 text-left text-sm font-semibold">Last Synced</th>
                <th className="px-4 py-2 text-left text-sm font-semibold">Status</th>
                <th className="px-4 py-2 text-left text-sm font-semibold">Action</th>
              </tr>
            </thead>
            <tbody>
              {group.devices.map((device) => (
                <tr key={device.deviceId} className="border-b">
                  <td className="px-4 py-2 text-sm font-mono">{device.deviceId.slice(0, 16)}...</td>
                  <td className="px-4 py-2 text-sm capitalize">{device.deviceType}</td>
                  <td className="px-4 py-2 text-sm">{new Date(device.joinedAt).toLocaleDateString()}</td>
                  <td className="px-4 py-2 text-sm">
                    {device.lastSyncedAt ? new Date(device.lastSyncedAt).toLocaleString() : 'Never'}
                  </td>
                  <td className="px-4 py-2 text-sm">
                    <span
                      className={`px-2 py-1 rounded text-xs font-semibold ${
                        device.status === 'active'
                          ? 'bg-green-100 text-green-800'
                          : 'bg-gray-100 text-gray-800'
                      }`}
                    >
                      {device.status}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-sm">
                    <button
                      onClick={() => handleRemoveDevice(device.deviceId)}
                      className="text-red-500 hover:underline"
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* History */}
      <div className="bg-white p-6 rounded-lg border">
        <h2 className="text-xl font-bold mb-4">History</h2>
        <div className="space-y-2 max-h-96 overflow-y-auto">
          {group.history.map((entry, idx) => (
            <div key={idx} className="p-2 bg-gray-50 rounded text-sm">
              <p className="font-semibold capitalize">{entry.action.replace(/_/g, ' ')}</p>
              <p className="text-xs text-gray-600">
                {new Date(entry.timestamp).toLocaleString()}
              </p>
              {entry.newPlan && (
                <p className="text-xs">
                  {entry.previousPlan} → {entry.newPlan}
                </p>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
