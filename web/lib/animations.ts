/**
 * Shared Framer Motion animation variants for consistent UI animations.
 *
 * Usage:
 *   import { fadeIn, staggerContainer, staggerItem } from '@/lib/animations'
 *   <motion.div variants={fadeIn} initial="hidden" animate="visible" />
 */

import type { Variants, Transition } from 'framer-motion'

// ─────────────────────────────────────────────────────────────
// Basic Transitions
// ─────────────────────────────────────────────────────────────

export const springTransition: Transition = {
  type: 'spring',
  stiffness: 300,
  damping: 30,
}

export const smoothTransition: Transition = {
  type: 'tween',
  ease: [0.25, 0.1, 0.25, 1],
  duration: 0.3,
}

// ─────────────────────────────────────────────────────────────
// Fade / Scale / Slide Variants
// ─────────────────────────────────────────────────────────────

export const fadeIn: Variants = {
  hidden: { opacity: 0 },
  visible: { opacity: 1, transition: { duration: 0.3, ease: 'easeOut' } },
  exit: { opacity: 0, transition: { duration: 0.2, ease: 'easeIn' } },
}

export const slideUp: Variants = {
  hidden: { opacity: 0, y: 10 },
  visible: { opacity: 1, y: 0, transition: { duration: 0.3, ease: 'easeOut' } },
  exit: { opacity: 0, y: 10, transition: { duration: 0.2, ease: 'easeIn' } },
}

export const slideInLeft: Variants = {
  hidden: { opacity: 0, x: -20 },
  visible: { opacity: 1, x: 0, transition: smoothTransition },
  exit: { opacity: 0, x: -20, transition: { duration: 0.2 } },
}

export const slideInRight: Variants = {
  hidden: { opacity: 0, x: 20 },
  visible: { opacity: 1, x: 0, transition: smoothTransition },
  exit: { opacity: 0, x: 20, transition: { duration: 0.2 } },
}

export const scaleIn: Variants = {
  hidden: { opacity: 0, scale: 0.95 },
  visible: { opacity: 1, scale: 1, transition: { type: 'spring', stiffness: 300, damping: 24 } },
  exit: { opacity: 0, scale: 0.95, transition: { duration: 0.15 } },
}

// ─────────────────────────────────────────────────────────────
// Stagger (for lists)
// ─────────────────────────────────────────────────────────────

export const staggerContainer: Variants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: {
      staggerChildren: 0.04,
      delayChildren: 0.05,
    },
  },
}

export const staggerItem: Variants = {
  hidden: { opacity: 0, y: 8 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { type: 'spring', stiffness: 300, damping: 24 },
  },
}

// ─────────────────────────────────────────────────────────────
// Message Bubble Animations
// ─────────────────────────────────────────────────────────────

export const messageBubbleIn: Variants = {
  hidden: { opacity: 0, y: 12, scale: 0.97 },
  visible: {
    opacity: 1,
    y: 0,
    scale: 1,
    transition: {
      type: 'spring',
      stiffness: 400,
      damping: 25,
      mass: 0.5,
    },
  },
}

// ─────────────────────────────────────────────────────────────
// Page Transitions
// ─────────────────────────────────────────────────────────────

export const pageTransition: Variants = {
  hidden: { opacity: 0, y: 8 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.3, ease: [0.25, 0.1, 0.25, 1] },
  },
  exit: {
    opacity: 0,
    y: -8,
    transition: { duration: 0.2, ease: 'easeIn' },
  },
}

// ─────────────────────────────────────────────────────────────
// Micro-interactions
// ─────────────────────────────────────────────────────────────

/** Use with whileHover and whileTap on buttons */
export const microBounce = {
  hover: { scale: 1.03, transition: { type: 'spring', stiffness: 400, damping: 20 } },
  tap: { scale: 0.97 },
}

/** Subtle press for larger interactive areas */
export const subtlePress = {
  hover: { scale: 1.01 },
  tap: { scale: 0.99 },
}

/** Floating animation for empty states */
export const floatingAnimation = {
  animate: {
    y: [0, -6, 0],
    transition: {
      duration: 3,
      repeat: Infinity,
      ease: 'easeInOut' as const,
    },
  },
}
