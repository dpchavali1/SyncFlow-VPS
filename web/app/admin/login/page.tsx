'use client'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { Shield, Loader2, Eye, EyeOff, AlertTriangle } from 'lucide-react'
import { vpsService } from '@/lib/vps'

export default function AdminLoginPage() {
  const router = useRouter()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState('')
  const [isLoading, setIsLoading] = useState(false)

  // Check if already authenticated on mount
  useEffect(() => {
    const checkExistingSession = () => {
      try {
        const sessionStr = sessionStorage.getItem('syncflow_admin_session')
        if (sessionStr) {
          const session = JSON.parse(sessionStr)
          if (session.authenticated && Date.now() < session.expiresAt) {
            router.push('/admin/cleanup')
            return
          } else {
            // Session expired
            sessionStorage.removeItem('syncflow_admin_session')
          }
        }
      } catch (err) {
        console.error('Session check error:', err)
        sessionStorage.removeItem('syncflow_admin_session')
      }
    }

    checkExistingSession()
  }, [router])

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setIsLoading(true)

    try {
      const result = await vpsService.adminLogin(username, password)
      localStorage.setItem('syncflow_user_id', result.userId)

      const adminSession = {
        authenticated: true,
        timestamp: Date.now(),
        expiresAt: Date.now() + (24 * 60 * 60 * 1000), // 24 hours
        username: username,
        role: 'admin'
      }

      sessionStorage.setItem('syncflow_admin_session', JSON.stringify(adminSession))

      // Redirect to admin dashboard
      router.push('/admin/cleanup')
    } catch (error: any) {
      console.error('Login error:', error)
      const msg = error?.message || ''
      if (msg.includes('401') || msg.includes('Invalid credentials')) {
        setError('Invalid username or password.')
      } else if (msg.includes('Failed to fetch') || msg.includes('NetworkError') || msg.includes('502')) {
        setError('Cannot reach API server. Check that the VPS is running.')
      } else {
        setError(`Login failed: ${msg || 'Unknown error'}`)
      }
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-900 flex items-center justify-center">
      <div className="px-6 py-8">
        <div className="text-center mb-8">
          <Shield className="w-16 h-16 text-blue-500 mx-auto mb-6" />
          <h1 className="text-3xl font-bold text-white mb-4">SyncFlow Admin</h1>
          <p className="text-gray-400 mb-8 max-w-md">
            Enter your admin username and password to access the data cleanup dashboard.
          </p>
        </div>

        <div className="max-w-md mx-auto">
          <form onSubmit={handleLogin} className="space-y-6">
            {error && (
              <div className="flex items-center gap-3 p-4 bg-red-900/50 border border-red-700 rounded-lg">
                <AlertTriangle className="w-5 h-5 text-red-400 flex-shrink-0" />
                <p className="text-red-300 text-sm">{error}</p>
              </div>
            )}

            <div>
              <label htmlFor="username" className="block text-sm font-medium text-gray-300 mb-2">
                Admin Username
              </label>
               <input
                 id="username"
                 type="text"
                 value={username}
                 onChange={(e) => setUsername(e.target.value)}
                 className="w-full px-4 py-3 bg-gray-700 border border-gray-600 rounded-lg text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                 placeholder="admin"
                 required
                 autoComplete="username"
               />
            </div>

            <div>
              <label htmlFor="password" className="block text-sm font-medium text-gray-300 mb-2">
                Password
              </label>
              <div className="relative">
                <input
                  id="password"
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full px-4 py-3 bg-gray-700 border border-gray-600 rounded-lg text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent pr-12"
                  placeholder="Enter password"
                  required
                  autoComplete="current-password"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3 top-1/2 transform -translate-y-1/2 text-gray-400 hover:text-gray-300"
                >
                  {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
                </button>
              </div>
            </div>

            <button
              type="submit"
              disabled={isLoading}
              className="w-full flex items-center justify-center gap-2 px-6 py-3 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 disabled:bg-gray-600 text-white font-semibold rounded-lg transition-colors"
            >
              {isLoading ? (
                <>
                  <Loader2 className="w-5 h-5 animate-spin" />
                  Authenticating...
                </>
              ) : (
                <>
                  <Shield className="w-5 h-5" />
                  Login as Admin
                </>
              )}
            </button>
          </form>

          <p className="text-center text-gray-600 text-xs mt-6">
            Session expires after 24 hours of inactivity
          </p>
        </div>
      </div>
    </div>
  )
}
