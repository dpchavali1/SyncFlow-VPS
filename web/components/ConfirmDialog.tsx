'use client'

import { useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { AlertTriangle } from 'lucide-react'
import { scaleIn, fadeIn } from '@/lib/animations'

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

  const canConfirm = !requireTyping || typedText === confirmText

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          variants={fadeIn}
          initial="hidden"
          animate="visible"
          exit="exit"
          className="fixed inset-0 bg-black/40 backdrop-blur-sm flex items-center justify-center z-50"
        >
          <motion.div
            variants={scaleIn}
            initial="hidden"
            animate="visible"
            exit="exit"
            className="glass-elevated rounded-3xl p-6 max-w-md w-full mx-4 shadow-2xl"
          >
            <div className="flex items-start gap-4">
              <div className="flex-shrink-0 w-12 h-12 rounded-2xl bg-red-500/10 flex items-center justify-center">
                <AlertTriangle className="w-6 h-6 text-red-500" />
              </div>
              <div className="flex-1">
                <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-2">{title}</h3>
                <p className="text-sm text-gray-500 dark:text-gray-400 whitespace-pre-line leading-relaxed">
                  {message}
                </p>
              </div>
            </div>

            {requireTyping && (
              <div className="mt-4">
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                  Type &apos;{confirmText}&apos; to confirm:
                </label>
                <input
                  type="text"
                  value={typedText}
                  onChange={(e) => setTypedText(e.target.value)}
                  className="w-full px-4 py-2.5 glass-input rounded-xl text-gray-900 dark:text-white text-sm focus:outline-none"
                  autoFocus
                />
              </div>
            )}

            <div className="flex gap-3 mt-6">
              <motion.button
                whileHover={{ scale: 1.02 }}
                whileTap={{ scale: 0.98 }}
                onClick={onCancel}
                className="flex-1 px-4 py-2.5 glass-panel rounded-xl hover:bg-white/80 dark:hover:bg-white/10 text-gray-700 dark:text-gray-200 text-sm font-medium transition-all"
              >
                Cancel
              </motion.button>
              <motion.button
                whileHover={{ scale: canConfirm ? 1.02 : 1 }}
                whileTap={{ scale: canConfirm ? 0.98 : 1 }}
                onClick={() => {
                  if (canConfirm) {
                    onConfirm()
                    setTypedText('')
                  }
                }}
                disabled={!canConfirm}
                className="flex-1 px-4 py-2.5 bg-gradient-to-r from-red-500 to-red-600 text-white rounded-xl text-sm font-medium hover:from-red-600 hover:to-red-700 disabled:from-gray-400 disabled:to-gray-500 disabled:cursor-not-allowed transition-all shadow-md shadow-red-500/20 disabled:shadow-none"
              >
                {confirmButtonText}
              </motion.button>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}
