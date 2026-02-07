'use client'

import { useRouter } from 'next/navigation'
import { Smartphone, LogOut, Settings, Menu, Download } from 'lucide-react'
import { useAppStore } from '@/lib/store'

export default function Header() {
  const router = useRouter()
  const { toggleSidebar } = useAppStore()

  const handleLogout = () => {
    localStorage.removeItem('syncflow_user_id')
    useAppStore.getState().setUserId(null)
    router.push('/')
  }

  return (
    <header className="flex-shrink-0 bg-white dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700 px-4 py-3">
      <div className="flex items-center justify-between">
        {/* Left: Logo and Title */}
        <div className="flex items-center">
          <button
            onClick={toggleSidebar}
            className="mr-3 p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 lg:hidden"
          >
            <Menu className="w-5 h-5 text-gray-600 dark:text-gray-400" />
          </button>

          <Smartphone className="w-6 h-6 text-blue-600 mr-2" />
          <h1 className="text-xl font-bold text-gray-900 dark:text-white">SyncFlow</h1>
        </div>

        {/* Right: Actions */}
        <div className="flex items-center gap-2">
          <button
            onClick={() => router.push('/download')}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-gradient-to-r from-blue-600 to-purple-600 hover:from-blue-700 hover:to-purple-700 text-white font-medium transition-all hover:scale-105 shadow-sm"
            title="Download Apps"
          >
            <Download className="w-4 h-4" />
            <span className="hidden sm:inline text-sm">Download</span>
          </button>

          <button
            onClick={() => router.push('/settings')}
            className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-600 dark:text-gray-400"
            title="Settings"
          >
            <Settings className="w-5 h-5" />
          </button>

          <button
            onClick={handleLogout}
            className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-600 dark:text-gray-400"
            title="Logout"
          >
            <LogOut className="w-5 h-5" />
          </button>
        </div>
      </div>
    </header>
  )
}
