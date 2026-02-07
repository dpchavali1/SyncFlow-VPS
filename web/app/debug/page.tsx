'use client'

import { useState } from 'react'
import { database } from '@/lib/firebase'
import { ref, get } from 'firebase/database'

export default function DebugPage() {
  const [result, setResult] = useState<string>('')
  const [loading, setLoading] = useState(false)

  const checkUserId = async (userId: string) => {
    setLoading(true)
    try {
      const messagesRef = ref(database, `users/${userId}/messages`)
      const snapshot = await get(messagesRef)

      if (snapshot.exists()) {
        const data = snapshot.val()
        const count = Object.keys(data).length
        setResult(`✅ Found ${count} messages for user: ${userId}`)
      } else {
        setResult(`❌ No messages found for user: ${userId}`)
      }
    } catch (error: any) {
      setResult(`❌ Error: ${error.message}`)
    }
    setLoading(false)
  }

  const scanAllUsers = async () => {
    setLoading(true)
    try {
      const usersRef = ref(database, 'users')
      const snapshot = await get(usersRef)

      if (snapshot.exists()) {
        const users = snapshot.val()
        let results = '=== All Users in Firebase ===\n\n'

        for (const [userId, userData] of Object.entries(users)) {
          const userMessages = (userData as any).messages || {}
          const messageCount = Object.keys(userMessages).length
          results += `User ID: ${userId}\n`
          results += `Messages: ${messageCount}\n\n`
        }

        setResult(results)
      } else {
        setResult('No users found in Firebase')
      }
    } catch (error: any) {
      setResult(`Error: ${error.message}`)
    }
    setLoading(false)
  }

  const storedUserId = typeof window !== 'undefined'
    ? localStorage.getItem('syncflow_user_id')
    : null

  return (
    <div className="min-h-screen bg-gray-100 dark:bg-gray-900 p-8">
      <div className="max-w-4xl mx-auto bg-white dark:bg-gray-800 rounded-lg shadow-lg p-6">
        <h1 className="text-2xl font-bold text-gray-900 dark:text-white mb-6">
          Firebase Debug Tool
        </h1>

        <div className="space-y-4">
          {/* Current User ID */}
          <div className="bg-blue-50 dark:bg-blue-900/20 p-4 rounded">
            <h2 className="font-semibold text-gray-900 dark:text-white mb-2">
              Current User ID (from localStorage):
            </h2>
            <code className="text-sm bg-white dark:bg-gray-700 px-3 py-1 rounded">
              {storedUserId || 'Not set'}
            </code>
          </div>

          {/* Check Current User */}
          <button
            onClick={() => storedUserId && checkUserId(storedUserId)}
            disabled={!storedUserId || loading}
            className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-gray-400 text-white font-semibold py-3 px-6 rounded-lg transition-colors"
          >
            Check Current User's Messages
          </button>

          {/* Scan All Users */}
          <button
            onClick={scanAllUsers}
            disabled={loading}
            className="w-full bg-green-600 hover:bg-green-700 disabled:bg-gray-400 text-white font-semibold py-3 px-6 rounded-lg transition-colors"
          >
            Scan All Users in Firebase
          </button>

          {/* Manual User ID Check */}
          <div className="border-t pt-4">
            <h3 className="font-semibold text-gray-900 dark:text-white mb-2">
              Check Specific User ID:
            </h3>
            <form
              onSubmit={(e) => {
                e.preventDefault()
                const formData = new FormData(e.currentTarget)
                const userId = formData.get('userId') as string
                if (userId) checkUserId(userId)
              }}
              className="flex gap-2"
            >
              <input
                type="text"
                name="userId"
                placeholder="Enter user ID"
                className="flex-1 px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-700 text-gray-900 dark:text-white"
              />
              <button
                type="submit"
                disabled={loading}
                className="bg-purple-600 hover:bg-purple-700 disabled:bg-gray-400 text-white font-semibold px-6 py-2 rounded-lg transition-colors"
              >
                Check
              </button>
            </form>
          </div>

          {/* Loading */}
          {loading && (
            <div className="flex items-center justify-center py-8">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
            </div>
          )}

          {/* Result */}
          {result && !loading && (
            <div className="bg-gray-50 dark:bg-gray-700 p-4 rounded">
              <h3 className="font-semibold text-gray-900 dark:text-white mb-2">
                Result:
              </h3>
              <pre className="text-sm text-gray-700 dark:text-gray-300 whitespace-pre-wrap">
                {result}
              </pre>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
