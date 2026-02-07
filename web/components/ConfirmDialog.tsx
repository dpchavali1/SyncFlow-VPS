'use client'

import { useState } from 'react'
import { AlertTriangle } from 'lucide-react'

interface ConfirmDialogProps {
  open: boolean
  title: string
  message: string
  confirmText?: string
  confirmButtonText?: string
  onConfirm: () => void
  onCancel: () => void
  requireTyping?: boolean
}

export function ConfirmDialog({
  open,
  title,
  message,
  confirmText = '',
  confirmButtonText = 'Confirm',
  onConfirm,
  onCancel,
  requireTyping = false,
}: ConfirmDialogProps) {
  const [typedText, setTypedText] = useState('')

  if (!open) return null

  const canConfirm = !requireTyping || typedText === confirmText

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white dark:bg-gray-800 rounded-lg p-6 max-w-md w-full shadow-xl">
        <div className="flex items-start gap-4">
          <div className="flex-shrink-0 w-12 h-12 rounded-full bg-red-100 dark:bg-red-900/30 flex items-center justify-center">
            <AlertTriangle className="w-6 h-6 text-red-600 dark:text-red-400" />
          </div>
          <div className="flex-1">
            <h3 className="text-lg font-semibold mb-2">{title}</h3>
            <p className="text-sm text-gray-600 dark:text-gray-400 whitespace-pre-line">
              {message}
            </p>
          </div>
        </div>

        {requireTyping && (
          <div className="mt-4">
            <label className="block text-sm font-medium mb-2">
              Type &apos;{confirmText}&apos; to confirm:
            </label>
            <input
              type="text"
              value={typedText}
              onChange={(e) => setTypedText(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-700 text-gray-900 dark:text-white"
              autoFocus
            />
          </div>
        )}

        <div className="flex gap-3 mt-6">
          <button
            onClick={onCancel}
            className="flex-1 px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-700 text-gray-900 dark:text-white"
          >
            Cancel
          </button>
          <button
            onClick={() => {
              if (canConfirm) {
                onConfirm()
                setTypedText('')
              }
            }}
            disabled={!canConfirm}
            className="flex-1 px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {confirmButtonText}
          </button>
        </div>
      </div>
    </div>
  )
}
