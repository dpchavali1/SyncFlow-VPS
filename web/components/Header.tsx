'use client'

import { useRouter } from 'next/navigation'
import { Smartphone, LogOut, Settings, Menu, Download } from 'lucide-react'
import { motion } from 'framer-motion'
import { useAppStore } from '@/lib/store'
import { unpairDevice } from '@/lib/auth'

export default function Header() {
  const router = useRouter()
  const { toggleSidebar } = useAppStore()

  const handleLogout = async () => {
    // Remove device from server (broadcasts device_removed to Android), clear tokens, disconnect WebSocket
    await unpairDevice()
    useAppStore.getState().setUserId(null)
    useAppStore.getState().setMessages([])
    router.push('/')
  }

  return (
    <header className="flex-shrink-0 glass-panel border-b-0 px-4 py-3 relative z-20">
      <div className="flex items-center justify-between">
        {/* Left: Logo and Title */}
        <div className="flex items-center">
          <motion.button
            onClick={toggleSidebar}
            className="mr-3 p-2 rounded-xl hover:bg-surface-100 dark:hover:bg-surface-700/50 lg:hidden transition-colors"
            whileTap={{ scale: 0.92 }}
          >
            <Menu className="w-5 h-5 text-surface-500 dark:text-surface-400" />
          </motion.button>

          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-xl bg-gradient-to-br from-primary-500 to-accent-500 flex items-center justify-center shadow-glow">
              <Smartphone className="w-4.5 h-4.5 text-white" strokeWidth={2.5} />
            </div>
            <h1 className="text-xl font-bold text-gradient tracking-tight">SyncFlow</h1>
          </div>
        </div>

        {/* Right: Actions */}
        <div className="flex items-center gap-1.5">
          <motion.button
            onClick={() => router.push('/download')}
            className="flex items-center gap-1.5 px-4 py-2 rounded-pill bg-gradient-to-r from-primary-500 via-primary-600 to-accent-600 text-white font-medium text-sm shadow-glow transition-shadow hover:shadow-lg"
            whileHover={{ scale: 1.03 }}
            whileTap={{ scale: 0.97 }}
            title="Download Apps"
          >
            <Download className="w-4 h-4" />
            <span className="hidden sm:inline">Download</span>
          </motion.button>

          <motion.button
            onClick={() => router.push('/settings')}
            className="p-2.5 rounded-xl hover:bg-surface-100 dark:hover:bg-surface-700/50 text-surface-500 dark:text-surface-400 transition-colors"
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.92 }}
            title="Settings"
          >
            <Settings className="w-[18px] h-[18px]" />
          </motion.button>

          <motion.button
            onClick={handleLogout}
            className="p-2.5 rounded-xl hover:bg-red-50 dark:hover:bg-red-900/20 text-surface-500 dark:text-surface-400 hover:text-red-500 dark:hover:text-red-400 transition-colors"
            whileHover={{ scale: 1.05 }}
            whileTap={{ scale: 0.92 }}
            title="Logout"
          >
            <LogOut className="w-[18px] h-[18px]" />
          </motion.button>
        </div>
      </div>
    </header>
  )
}
