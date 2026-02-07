/**
 * User-Friendly Error Messages
 *
 * Converts technical errors into actionable, user-friendly messages
 */

export interface ErrorContext {
  operation?: string
  technicalMessage?: string
  code?: string
  retryable?: boolean
  actionRequired?: string
}

export interface UserError {
  title: string
  message: string
  actionButton?: string
  actionUrl?: string
  canRetry: boolean
  severity: 'error' | 'warning' | 'info'
}

/**
 * Convert Firebase errors to user-friendly messages
 */
export function getFirebaseErrorMessage(error: any): UserError {
  const code = error?.code || error?.message || ''
  const message = error?.message || ''

  // Authentication errors
  if (code.includes('auth/')) {
    return getAuthErrorMessage(code, message)
  }

  // Database errors
  if (code.includes('database/') || code.includes('permission-denied')) {
    return getDatabaseErrorMessage(code, message)
  }

  // Network errors
  if (code.includes('network') || code.includes('NETWORK') || message.includes('Failed to fetch')) {
    return {
      title: 'Connection Problem',
      message: 'Unable to connect to the server. Please check your internet connection and try again.',
      actionButton: 'Retry',
      canRetry: true,
      severity: 'error',
    }
  }

  // Functions errors
  if (code.includes('functions/')) {
    return getFunctionsErrorMessage(code, message)
  }

  // Storage errors
  if (code.includes('storage/')) {
    return getStorageErrorMessage(code, message)
  }

  // Generic Firebase error
  return {
    title: 'Something Went Wrong',
    message: 'An unexpected error occurred. Please try again in a moment.',
    actionButton: 'Retry',
    canRetry: true,
    severity: 'error',
  }
}

/**
 * Authentication error messages
 */
function getAuthErrorMessage(code: string, technicalMessage: string): UserError {
  const authErrors: Record<string, UserError> = {
    'auth/user-not-found': {
      title: 'Account Not Found',
      message: 'No account exists with this information. Please check and try again.',
      canRetry: false,
      severity: 'error',
    },
    'auth/wrong-password': {
      title: 'Incorrect Password',
      message: 'The password you entered is incorrect. Please try again.',
      canRetry: true,
      severity: 'error',
    },
    'auth/invalid-email': {
      title: 'Invalid Email',
      message: 'Please enter a valid email address.',
      canRetry: true,
      severity: 'error',
    },
    'auth/email-already-in-use': {
      title: 'Email Already Registered',
      message: 'An account with this email already exists. Try signing in instead.',
      actionButton: 'Sign In',
      canRetry: false,
      severity: 'warning',
    },
    'auth/weak-password': {
      title: 'Weak Password',
      message: 'Password must be at least 6 characters long.',
      canRetry: true,
      severity: 'warning',
    },
    'auth/too-many-requests': {
      title: 'Too Many Attempts',
      message: 'Too many failed attempts. Please wait a few minutes and try again.',
      canRetry: false,
      severity: 'error',
    },
    'auth/network-request-failed': {
      title: 'Connection Problem',
      message: 'Unable to connect to authentication server. Check your internet and try again.',
      actionButton: 'Retry',
      canRetry: true,
      severity: 'error',
    },
    'auth/invalid-custom-token': {
      title: 'Session Expired',
      message: 'Your session has expired. Please sign in again.',
      actionButton: 'Sign In',
      actionUrl: '/',
      canRetry: false,
      severity: 'warning',
    },
  }

  return authErrors[code] || {
    title: 'Authentication Error',
    message: 'Unable to sign in. Please check your credentials and try again.',
    actionButton: 'Retry',
    canRetry: true,
    severity: 'error',
  }
}

/**
 * Database error messages
 */
function getDatabaseErrorMessage(code: string, technicalMessage: string): UserError {
  if (code.includes('permission-denied')) {
    return {
      title: 'Access Denied',
      message: 'You don\'t have permission to access this data. Try signing in again.',
      actionButton: 'Sign In',
      actionUrl: '/',
      canRetry: false,
      severity: 'error',
    }
  }

  if (code.includes('disconnected')) {
    return {
      title: 'Connection Lost',
      message: 'Lost connection to server. Your changes will sync when you\'re back online.',
      canRetry: true,
      severity: 'warning',
    }
  }

  if (technicalMessage.includes('timeout')) {
    return {
      title: 'Request Timed Out',
      message: 'The operation took too long. Please check your connection and try again.',
      actionButton: 'Retry',
      canRetry: true,
      severity: 'error',
    }
  }

  return {
    title: 'Database Error',
    message: 'Unable to load data. Please refresh the page and try again.',
    actionButton: 'Refresh',
    canRetry: true,
    severity: 'error',
  }
}

/**
 * Cloud Functions error messages
 */
function getFunctionsErrorMessage(code: string, technicalMessage: string): UserError {
  const functionsErrors: Record<string, UserError> = {
    'functions/not-found': {
      title: 'Feature Unavailable',
      message: 'This feature is temporarily unavailable. Please try again later.',
      canRetry: true,
      severity: 'warning',
    },
    'functions/unauthenticated': {
      title: 'Sign In Required',
      message: 'Please sign in to continue.',
      actionButton: 'Sign In',
      actionUrl: '/',
      canRetry: false,
      severity: 'error',
    },
    'functions/permission-denied': {
      title: 'Permission Denied',
      message: 'You don\'t have permission to perform this action.',
      canRetry: false,
      severity: 'error',
    },
    'functions/deadline-exceeded': {
      title: 'Request Timed Out',
      message: 'The operation took too long. Please try again.',
      actionButton: 'Retry',
      canRetry: true,
      severity: 'error',
    },
    'functions/resource-exhausted': {
      title: 'Too Many Requests',
      message: 'You\'ve made too many requests. Please wait a moment and try again.',
      canRetry: false,
      severity: 'warning',
    },
  }

  return functionsErrors[code] || {
    title: 'Service Unavailable',
    message: 'Unable to complete your request. Please try again in a moment.',
    actionButton: 'Retry',
    canRetry: true,
    severity: 'error',
  }
}

/**
 * Storage error messages
 */
function getStorageErrorMessage(code: string, technicalMessage: string): UserError {
  const storageErrors: Record<string, UserError> = {
    'storage/object-not-found': {
      title: 'File Not Found',
      message: 'The requested file could not be found.',
      canRetry: false,
      severity: 'error',
    },
    'storage/unauthorized': {
      title: 'Access Denied',
      message: 'You don\'t have permission to access this file.',
      canRetry: false,
      severity: 'error',
    },
    'storage/canceled': {
      title: 'Upload Canceled',
      message: 'The file upload was canceled.',
      canRetry: true,
      severity: 'info',
    },
    'storage/quota-exceeded': {
      title: 'Storage Limit Reached',
      message: 'You\'ve reached your storage limit. Please free up space and try again.',
      canRetry: false,
      severity: 'error',
    },
  }

  return storageErrors[code] || {
    title: 'File Error',
    message: 'Unable to upload or download file. Please try again.',
    actionButton: 'Retry',
    canRetry: true,
    severity: 'error',
  }
}

/**
 * E2EE specific error messages
 */
export function getE2EEErrorMessage(error: any): UserError {
  const message = error?.message || ''

  if (message.includes('key') && message.includes('not found')) {
    return {
      title: 'Encryption Keys Missing',
      message: 'Unable to decrypt messages. Try syncing your encryption keys from your Android device.',
      actionButton: 'Sync Keys',
      actionUrl: '/settings',
      canRetry: false,
      severity: 'warning',
    }
  }

  if (message.includes('decrypt')) {
    return {
      title: 'Decryption Failed',
      message: 'Unable to decrypt this message. It may have been encrypted with different keys.',
      canRetry: false,
      severity: 'error',
    }
  }

  if (message.includes('passphrase') || message.includes('password')) {
    return {
      title: 'Incorrect Passphrase',
      message: 'The passphrase you entered is incorrect. Please try again.',
      actionButton: 'Try Again',
      canRetry: true,
      severity: 'error',
    }
  }

  if (message.includes('IndexedDB') || message.includes('storage')) {
    return {
      title: 'Storage Error',
      message: 'Unable to access secure storage. Try closing other tabs or clearing browser data.',
      canRetry: true,
      severity: 'error',
    }
  }

  return {
    title: 'Encryption Error',
    message: 'Unable to encrypt or decrypt data. Please try again.',
    actionButton: 'Retry',
    canRetry: true,
    severity: 'error',
  }
}

/**
 * Pairing specific error messages
 */
export function getPairingErrorMessage(error: any): UserError {
  const message = error?.message || ''
  const code = error?.code || ''

  if (message.includes('device limit') || message.includes('too many devices')) {
    return {
      title: 'Device Limit Reached',
      message: 'You\'ve reached the maximum number of paired devices. Unpair a device to continue.',
      actionButton: 'Manage Devices',
      actionUrl: '/settings',
      canRetry: false,
      severity: 'warning',
    }
  }

  if (message.includes('expired') || message.includes('timeout')) {
    return {
      title: 'Pairing Expired',
      message: 'The pairing session has expired. Please scan the QR code again.',
      actionButton: 'Try Again',
      canRetry: true,
      severity: 'warning',
    }
  }

  if (message.includes('rejected') || message.includes('denied')) {
    return {
      title: 'Pairing Rejected',
      message: 'The pairing request was rejected on the Android device.',
      actionButton: 'Try Again',
      canRetry: true,
      severity: 'info',
    }
  }

  if (code.includes('network') || message.includes('connection')) {
    return {
      title: 'Connection Problem',
      message: 'Unable to connect for pairing. Make sure both devices are connected to the internet.',
      actionButton: 'Retry',
      canRetry: true,
      severity: 'error',
    }
  }

  return {
    title: 'Pairing Failed',
    message: 'Unable to pair with Android device. Please try scanning the QR code again.',
    actionButton: 'Retry',
    canRetry: true,
    severity: 'error',
  }
}

/**
 * General operation error messages
 */
export function getOperationErrorMessage(operation: string, error: any): UserError {
  const operationMessages: Record<string, (error: any) => UserError> = {
    'send_message': () => ({
      title: 'Failed to Send',
      message: 'Your message couldn\'t be sent. Check your connection and try again.',
      actionButton: 'Retry',
      canRetry: true,
      severity: 'error',
    }),
    'load_messages': () => ({
      title: 'Failed to Load Messages',
      message: 'Unable to load your messages. Please refresh the page.',
      actionButton: 'Refresh',
      canRetry: true,
      severity: 'error',
    }),
    'load_contacts': () => ({
      title: 'Failed to Load Contacts',
      message: 'Unable to load your contacts. Please refresh the page.',
      actionButton: 'Refresh',
      canRetry: true,
      severity: 'error',
    }),
    'delete_message': () => ({
      title: 'Failed to Delete',
      message: 'Unable to delete the message. Please try again.',
      actionButton: 'Retry',
      canRetry: true,
      severity: 'error',
    }),
    'upload_attachment': () => ({
      title: 'Upload Failed',
      message: 'Unable to upload the attachment. Please check the file size and try again.',
      actionButton: 'Retry',
      canRetry: true,
      severity: 'error',
    }),
  }

  const getSpecificError = operationMessages[operation]
  if (getSpecificError) {
    return getSpecificError(error)
  }

  // Fall back to Firebase error handler
  return getFirebaseErrorMessage(error)
}

/**
 * Format error for display in UI
 */
export function formatErrorForDisplay(error: any, context?: ErrorContext): UserError {
  if (!error) {
    return {
      title: 'Unknown Error',
      message: 'An unexpected error occurred.',
      canRetry: true,
      severity: 'error',
    }
  }

  // Check specific operation
  if (context?.operation) {
    return getOperationErrorMessage(context.operation, error)
  }

  // Check for E2EE errors
  if (context?.operation?.includes('e2ee') || context?.operation?.includes('decrypt')) {
    return getE2EEErrorMessage(error)
  }

  // Check for pairing errors
  if (context?.operation?.includes('pair')) {
    return getPairingErrorMessage(error)
  }

  // Default to Firebase error handler
  return getFirebaseErrorMessage(error)
}
