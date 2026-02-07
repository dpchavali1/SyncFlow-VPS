'use client'

import Link from 'next/link'
import { Users, Database, Trash2, LogOut, Table2 } from 'lucide-react'

export default function AdminPage() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-gray-50 to-gray-100 p-8">
      <div className="max-w-6xl mx-auto">
        {/* Header */}
        <div className="mb-12">
          <h1 className="text-4xl font-bold text-gray-900 mb-2">Admin Dashboard</h1>
          <p className="text-gray-600">Manage users, sync groups, and system health</p>
        </div>

        {/* Admin Menu Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mb-12">
          {/* Users Card */}
          <AdminCard
            title="Users"
            description="View all users and their device usage"
            icon={<Users className="w-8 h-8" />}
            href="/admin/users"
            color="blue"
          />

          {/* Sync Groups Card */}
          <AdminCard
            title="Sync Groups"
            description="Manage device sync groups and subscriptions"
            icon={<Database className="w-8 h-8" />}
            href="/admin/sync-groups"
            color="green"
          />

          {/* Database Card */}
          <AdminCard
            title="Database"
            description="Browse tables, edit rows, and run SQL queries"
            icon={<Table2 className="w-8 h-8" />}
            href="/admin/database"
            color="purple"
          />

          {/* Cleanup Card */}
          <AdminCard
            title="Cleanup & Maintenance"
            description="Run cleanup tasks, browse database, and optimize"
            icon={<Trash2 className="w-8 h-8" />}
            href="/admin/cleanup"
            color="red"
          />

          {/* Logout Card */}
          <AdminCard
            title="Logout"
            description="Exit admin dashboard and return to app"
            icon={<LogOut className="w-8 h-8" />}
            href="/"
            color="gray"
          />
        </div>

        {/* Quick Stats */}
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="text-xl font-semibold text-gray-900 mb-4">Quick Links</h2>
          <ul className="space-y-2">
            <li>
              <Link href="/admin/users" className="text-blue-500 hover:underline">
                → View all users and device counts
              </Link>
            </li>
            <li>
              <Link href="/admin/sync-groups" className="text-blue-500 hover:underline">
                → View all sync groups
              </Link>
            </li>
            <li>
              <Link href="/admin/database" className="text-blue-500 hover:underline">
                → Browse database tables and run queries
              </Link>
            </li>
            <li>
              <Link href="/admin/cleanup" className="text-blue-500 hover:underline">
                → Run system cleanup & maintenance
              </Link>
            </li>
          </ul>
        </div>
      </div>
    </div>
  )
}

interface AdminCardProps {
  title: string
  description: string
  icon: React.ReactNode
  href: string
  color: 'blue' | 'green' | 'red' | 'gray' | 'purple'
}

function AdminCard({ title, description, icon, href, color }: AdminCardProps) {
  const colorClasses = {
    blue: 'bg-blue-50 hover:bg-blue-100 border-blue-200 text-blue-600',
    green: 'bg-green-50 hover:bg-green-100 border-green-200 text-green-600',
    red: 'bg-red-50 hover:bg-red-100 border-red-200 text-red-600',
    gray: 'bg-gray-50 hover:bg-gray-100 border-gray-200 text-gray-600',
    purple: 'bg-purple-50 hover:bg-purple-100 border-purple-200 text-purple-600'
  }

  return (
    <Link href={href}>
      <div
        className={`${colorClasses[color]} border rounded-lg p-6 cursor-pointer transition-all hover:shadow-lg`}
      >
        <div className="flex items-start gap-4">
          <div className="p-3 rounded-lg bg-white bg-opacity-50">{icon}</div>
          <div className="flex-1">
            <h3 className="text-lg font-semibold text-gray-900">{title}</h3>
            <p className="text-sm text-gray-600 mt-1">{description}</p>
          </div>
        </div>
      </div>
    </Link>
  )
}
