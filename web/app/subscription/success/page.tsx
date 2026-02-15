'use client'

export default function SubscriptionSuccessPage() {
  return (
    <div className="min-h-screen bg-gray-100 dark:bg-gray-900 flex items-center justify-center p-6">
      <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-lg p-8 max-w-md w-full text-center">
        <div className="text-5xl mb-4">&#10003;</div>
        <h1 className="text-2xl font-bold text-gray-900 dark:text-white mb-2">
          Payment Successful!
        </h1>
        <p className="text-gray-600 dark:text-gray-400 mb-6">
          Your SyncFlow Pro subscription is now active. You can close this tab and return to the app.
        </p>
        <p className="text-sm text-gray-500 dark:text-gray-500">
          Your plan will update automatically within a few seconds.
        </p>
      </div>
    </div>
  )
}
