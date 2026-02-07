import { useEffect, useRef, useState } from 'react'

/**
 * Custom hook to enforce proper Firebase listener cleanup
 *
 * This hook ensures that Firebase listeners are properly cleaned up when:
 * - Component unmounts
 * - Dependencies change
 * - User logs out or switches accounts
 *
 * @param listenerFn - Function that sets up the listener and returns an unsubscribe function
 * @param callback - Callback to handle data updates from the listener
 * @param dependencies - React dependencies that trigger listener restart
 *
 * @example
 * ```typescript
 * useFirebaseListener(
 *   (callback) => listenToMessages(userId, callback),
 *   (messages) => setMessages(messages),
 *   [userId]
 * )
 * ```
 */
export function useFirebaseListener<T>(
  listenerFn: (callback: (data: T) => void) => () => void,
  callback: (data: T) => void,
  dependencies: React.DependencyList
) {
  const unsubscribeRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    // Skip if dependencies are not ready (e.g., userId is null)
    if (dependencies.some(dep => dep === null || dep === undefined)) {
      console.log('[useFirebaseListener] Skipping listener - dependencies not ready')
      return
    }

    console.log('[useFirebaseListener] Starting listener')

    try {
      unsubscribeRef.current = listenerFn(callback)
    } catch (error) {
      console.error('[useFirebaseListener] Failed to start listener:', error)
    }

    // Cleanup function - called when dependencies change or component unmounts
    return () => {
      console.log('[useFirebaseListener] Cleaning up listener')
      if (unsubscribeRef.current) {
        try {
          unsubscribeRef.current()
        } catch (error) {
          console.error('[useFirebaseListener] Error during cleanup:', error)
        }
        unsubscribeRef.current = null
      }
    }
  }, dependencies)
}

/**
 * Custom hook for Firebase listeners that need to handle loading states
 *
 * @param listenerFn - Function that sets up the listener and returns an unsubscribe function
 * @param callback - Callback to handle data updates from the listener
 * @param dependencies - React dependencies that trigger listener restart
 * @returns Loading state boolean
 *
 * @example
 * ```typescript
 * const isLoading = useFirebaseListenerWithLoading(
 *   (callback) => listenToMessages(userId, callback),
 *   (messages) => {
 *     setMessages(messages)
 *   },
 *   [userId]
 * )
 * ```
 */
export function useFirebaseListenerWithLoading<T>(
  listenerFn: (callback: (data: T) => void) => () => void,
  callback: (data: T) => void,
  dependencies: React.DependencyList
): boolean {
  const unsubscribeRef = useRef<(() => void) | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    // Skip if dependencies are not ready
    if (dependencies.some(dep => dep === null || dep === undefined)) {
      setIsLoading(false)
      return
    }

    setIsLoading(true)
    console.log('[useFirebaseListenerWithLoading] Starting listener')

    try {
      let isFirstLoad = true
      unsubscribeRef.current = listenerFn((data) => {
        callback(data)
        if (isFirstLoad) {
          setIsLoading(false)
          isFirstLoad = false
        }
      })
    } catch (error) {
      console.error('[useFirebaseListenerWithLoading] Failed to start listener:', error)
      setIsLoading(false)
    }

    return () => {
      console.log('[useFirebaseListenerWithLoading] Cleaning up listener')
      if (unsubscribeRef.current) {
        try {
          unsubscribeRef.current()
        } catch (error) {
          console.error('[useFirebaseListenerWithLoading] Error during cleanup:', error)
        }
        unsubscribeRef.current = null
      }
    }
  }, dependencies)

  return isLoading
}
