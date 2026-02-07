'use client'

import { useState } from 'react'
import Link from 'next/link'

export default function DownloadPage() {
  const [copied, setCopied] = useState(false)

  const version = '1.0.0'
  const fileSize = '45 MB'
  const sha256 = 'Will be generated after build'
  const minMacOS = 'macOS 13.0 or later'

  const copyChecksum = () => {
    navigator.clipboard.writeText(sha256)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 to-blue-50 dark:from-slate-900 dark:to-slate-800">
      {/* Header */}
      <header className="border-b border-slate-200 dark:border-slate-700 bg-white/80 dark:bg-slate-900/80 backdrop-blur-sm sticky top-0 z-50">
        <div className="max-w-6xl mx-auto px-4 py-4 flex items-center justify-between">
          <Link href="/" className="text-2xl font-bold bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-transparent">
            SyncFlow
          </Link>
          <nav className="flex gap-6">
            <Link href="/privacy" className="text-slate-600 dark:text-slate-300 hover:text-blue-600">Privacy</Link>
            <Link href="/terms" className="text-slate-600 dark:text-slate-300 hover:text-blue-600">Terms</Link>
          </nav>
        </div>
      </header>

      <main className="max-w-5xl mx-auto px-4 py-16">
        {/* Hero Section */}
        <div className="text-center mb-16">
          <h1 className="text-5xl font-bold mb-4 bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-transparent">
            Download SyncFlow
          </h1>
          <p className="text-xl text-slate-600 dark:text-slate-300">
            Seamlessly sync your Android phone with your Mac
          </p>
        </div>

        {/* Download Cards */}
        <div className="grid md:grid-cols-2 gap-8 mb-12">
          {/* macOS Download Card */}
          <div className="bg-gradient-to-br from-blue-500 to-purple-600 rounded-3xl p-1 shadow-2xl">
            <div className="bg-white dark:bg-slate-900 rounded-3xl p-8">
              <div className="flex items-start gap-4 mb-6">
                {/* App Icon */}
                <div className="w-16 h-16 bg-gradient-to-br from-blue-500 to-purple-600 rounded-2xl flex items-center justify-center flex-shrink-0">
                  <svg className="w-8 h-8 text-white" fill="currentColor" viewBox="0 0 24 24">
                    <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z"/>
                  </svg>
                </div>
                <div className="flex-1">
                  <h2 className="text-2xl font-bold mb-1 dark:text-white">macOS</h2>
                  <p className="text-sm text-slate-600 dark:text-slate-300">
                    {minMacOS}
                  </p>
                  <p className="text-sm text-slate-600 dark:text-slate-300">
                    Apple Silicon & Intel • {fileSize}
                  </p>
                </div>
              </div>

              {/* Download Button */}
              <a
                href={`/downloads/SyncFlow-${version}.dmg`}
                className="flex items-center justify-center gap-2 w-full px-6 py-3 bg-gradient-to-r from-blue-600 to-purple-600 text-white rounded-xl font-semibold hover:shadow-lg hover:scale-105 transition-all duration-200"
              >
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3V10" />
                </svg>
                Download DMG
              </a>

              {/* Checksum */}
              <div className="mt-4 p-3 bg-slate-100 dark:bg-slate-800 rounded-lg">
                <p className="text-xs text-slate-500 dark:text-slate-400 mb-1">SHA-256:</p>
                <div className="flex items-center gap-2">
                  <code className="text-xs font-mono text-slate-700 dark:text-slate-300 flex-1 truncate">
                    {sha256}
                  </code>
                  <button
                    onClick={copyChecksum}
                    className="text-xs px-2 py-1 bg-white dark:bg-slate-700 rounded hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
                  >
                    {copied ? '✓' : 'Copy'}
                  </button>
                </div>
              </div>

              {/* Trust Badges */}
              <div className="flex flex-wrap gap-3 mt-4 pt-4 border-t border-slate-200 dark:border-slate-700">
                <div className="flex items-center gap-1 text-xs text-slate-600 dark:text-slate-400">
                  <svg className="w-4 h-4 text-green-500" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                  </svg>
                  Notarized
                </div>
                <div className="flex items-center gap-1 text-xs text-slate-600 dark:text-slate-400">
                  <svg className="w-4 h-4 text-green-500" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                  </svg>
                  Code Signed
                </div>
              </div>
            </div>
          </div>

          {/* Android Download Card */}
          <div className="bg-gradient-to-br from-green-500 to-teal-600 rounded-3xl p-1 shadow-2xl">
            <div className="bg-white dark:bg-slate-900 rounded-3xl p-8">
              <div className="flex items-start gap-4 mb-6">
                {/* App Icon */}
                <div className="w-16 h-16 bg-gradient-to-br from-green-500 to-teal-600 rounded-2xl flex items-center justify-center flex-shrink-0">
                  <svg className="w-8 h-8 text-white" fill="currentColor" viewBox="0 0 24 24">
                    <path d="M17.523 15.3414c-.5511 0-.9993-.4486-.9993-.9997s.4483-.9993.9993-.9993c.5511 0 .9993.4483.9993.9993.0001.5511-.4482.9997-.9993.9997m-11.046 0c-.5511 0-.9993-.4486-.9993-.9997s.4482-.9993.9993-.9993c.5511 0 .9993.4483.9993.9993 0 .5511-.4483.9997-.9993.9997m11.4045-6.02l1.9973-3.4592a.416.416 0 00-.1521-.5676.416.416 0 00-.5676.1521l-2.0223 3.503C15.5902 8.2439 13.8533 7.8508 12 7.8508s-3.5902.3931-5.1367 1.0989L4.841 5.4467a.4161.4161 0 00-.5677-.1521.4157.4157 0 00-.1521.5676l1.9973 3.4592C2.6889 11.1867.3432 14.6589 0 18.761h24c-.3435-4.1021-2.6892-7.5743-6.1185-9.4396"/>
                  </svg>
                </div>
                <div className="flex-1">
                  <h2 className="text-2xl font-bold mb-1 dark:text-white">Android</h2>
                  <p className="text-sm text-slate-600 dark:text-slate-300">
                    Android 8.0 or later
                  </p>
                  <p className="text-sm text-slate-600 dark:text-slate-300">
                    All devices • {fileSize}
                  </p>
                </div>
              </div>

              {/* Download Button */}
              <a
                href={`/downloads/SyncFlow-${version}.apk`}
                className="flex items-center justify-center gap-2 w-full px-6 py-3 bg-gradient-to-r from-green-600 to-teal-600 text-white rounded-xl font-semibold hover:shadow-lg hover:scale-105 transition-all duration-200"
              >
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3V10" />
                </svg>
                Download APK
              </a>

              {/* Checksum */}
              <div className="mt-4 p-3 bg-slate-100 dark:bg-slate-800 rounded-lg">
                <p className="text-xs text-slate-500 dark:text-slate-400 mb-1">SHA-256:</p>
                <div className="flex items-center gap-2">
                  <code className="text-xs font-mono text-slate-700 dark:text-slate-300 flex-1 truncate">
                    {sha256}
                  </code>
                  <button
                    onClick={copyChecksum}
                    className="text-xs px-2 py-1 bg-white dark:bg-slate-700 rounded hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
                  >
                    {copied ? '✓' : 'Copy'}
                  </button>
                </div>
              </div>

              {/* Trust Badges */}
              <div className="flex flex-wrap gap-3 mt-4 pt-4 border-t border-slate-200 dark:border-slate-700">
                <div className="flex items-center gap-1 text-xs text-slate-600 dark:text-slate-400">
                  <svg className="w-4 h-4 text-green-500" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                  </svg>
                  Code Signed
                </div>
                <div className="flex items-center gap-1 text-xs text-slate-600 dark:text-slate-400">
                  <svg className="w-4 h-4 text-green-500" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                  </svg>
                  Privacy Focused
                </div>
              </div>

              {/* Installation Note */}
              <div className="mt-4 p-3 bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-lg">
                <p className="text-xs text-yellow-800 dark:text-yellow-300">
                  <strong>Note:</strong> Enable "Install from unknown sources" in Android settings to install APK files.
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* Installation Guide */}
        <div className="bg-white dark:bg-slate-900 rounded-2xl p-8 mb-12 shadow-lg">
          <h2 className="text-2xl font-bold mb-6 dark:text-white">Installation Guide</h2>

          {/* macOS Installation */}
          <div className="mb-8">
            <h3 className="text-xl font-semibold mb-4 text-blue-600 dark:text-blue-400 flex items-center gap-2">
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z"/>
              </svg>
              macOS Installation
            </h3>
            <div className="space-y-4">
              <div className="flex gap-4">
                <div className="w-10 h-10 bg-blue-100 dark:bg-blue-900 rounded-full flex items-center justify-center flex-shrink-0">
                  <span className="text-blue-600 dark:text-blue-300 font-bold">1</span>
                </div>
                <div>
                  <h4 className="font-semibold mb-2 dark:text-white">Download the DMG file</h4>
                  <p className="text-slate-600 dark:text-slate-300">Click the "Download DMG" button above to get the SyncFlow installer.</p>
                </div>
              </div>

              <div className="flex gap-4">
                <div className="w-10 h-10 bg-blue-100 dark:bg-blue-900 rounded-full flex items-center justify-center flex-shrink-0">
                  <span className="text-blue-600 dark:text-blue-300 font-bold">2</span>
                </div>
                <div>
                  <h4 className="font-semibold mb-2 dark:text-white">Open the DMG file</h4>
                  <p className="text-slate-600 dark:text-slate-300">Double-click the downloaded DMG file. A window will open showing the SyncFlow app icon.</p>
                </div>
              </div>

              <div className="flex gap-4">
                <div className="w-10 h-10 bg-blue-100 dark:bg-blue-900 rounded-full flex items-center justify-center flex-shrink-0">
                  <span className="text-blue-600 dark:text-blue-300 font-bold">3</span>
                </div>
                <div>
                  <h4 className="font-semibold mb-2 dark:text-white">Drag to Applications folder</h4>
                  <p className="text-slate-600 dark:text-slate-300">Drag the SyncFlow icon to your Applications folder to install it.</p>
                </div>
              </div>

              <div className="flex gap-4">
                <div className="w-10 h-10 bg-blue-100 dark:bg-blue-900 rounded-full flex items-center justify-center flex-shrink-0">
                  <span className="text-blue-600 dark:text-blue-300 font-bold">4</span>
                </div>
                <div>
                  <h4 className="font-semibold mb-2 dark:text-white">Launch SyncFlow</h4>
                  <p className="text-slate-600 dark:text-slate-300">Open SyncFlow from your Applications folder and follow the pairing instructions.</p>
                </div>
              </div>
            </div>

            <div className="mt-6 p-4 bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-lg">
              <div className="flex gap-3">
                <svg className="w-6 h-6 text-yellow-600 dark:text-yellow-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                </svg>
                <div>
                  <h5 className="font-semibold text-yellow-900 dark:text-yellow-200 mb-1">First Launch Security</h5>
                  <p className="text-sm text-yellow-800 dark:text-yellow-300">
                    On first launch, macOS may show a security warning. Right-click the app and select "Open" to bypass this one-time warning.
                  </p>
                </div>
              </div>
            </div>
          </div>

          {/* Android Installation */}
          <div className="pt-8 border-t border-slate-200 dark:border-slate-700">
            <h3 className="text-xl font-semibold mb-4 text-green-600 dark:text-green-400 flex items-center gap-2">
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M17.523 15.3414c-.5511 0-.9993-.4486-.9993-.9997s.4483-.9993.9993-.9993c.5511 0 .9993.4483.9993.9993.0001.5511-.4482.9997-.9993.9997m-11.046 0c-.5511 0-.9993-.4486-.9993-.9997s.4482-.9993.9993-.9993c.5511 0 .9993.4483.9993.9993 0 .5511-.4483.9997-.9993.9997m11.4045-6.02l1.9973-3.4592a.416.416 0 00-.1521-.5676.416.416 0 00-.5676.1521l-2.0223 3.503C15.5902 8.2439 13.8533 7.8508 12 7.8508s-3.5902.3931-5.1367 1.0989L4.841 5.4467a.4161.4161 0 00-.5677-.1521.4157.4157 0 00-.1521.5676l1.9973 3.4592C2.6889 11.1867.3432 14.6589 0 18.761h24c-.3435-4.1021-2.6892-7.5743-6.1185-9.4396"/>
              </svg>
              Android Installation
            </h3>
            <div className="space-y-4">
              <div className="flex gap-4">
                <div className="w-10 h-10 bg-green-100 dark:bg-green-900 rounded-full flex items-center justify-center flex-shrink-0">
                  <span className="text-green-600 dark:text-green-300 font-bold">1</span>
                </div>
                <div>
                  <h4 className="font-semibold mb-2 dark:text-white">Download the APK file</h4>
                  <p className="text-slate-600 dark:text-slate-300">Click the "Download APK" button above to download the installer to your Android device.</p>
                </div>
              </div>

              <div className="flex gap-4">
                <div className="w-10 h-10 bg-green-100 dark:bg-green-900 rounded-full flex items-center justify-center flex-shrink-0">
                  <span className="text-green-600 dark:text-green-300 font-bold">2</span>
                </div>
                <div>
                  <h4 className="font-semibold mb-2 dark:text-white">Enable installation from unknown sources</h4>
                  <p className="text-slate-600 dark:text-slate-300">Go to Settings → Security → Allow installation from unknown sources. (Location may vary by device)</p>
                </div>
              </div>

              <div className="flex gap-4">
                <div className="w-10 h-10 bg-green-100 dark:bg-green-900 rounded-full flex items-center justify-center flex-shrink-0">
                  <span className="text-green-600 dark:text-green-300 font-bold">3</span>
                </div>
                <div>
                  <h4 className="font-semibold mb-2 dark:text-white">Open the APK file</h4>
                  <p className="text-slate-600 dark:text-slate-300">Tap the downloaded APK file in your notifications or file manager to begin installation.</p>
                </div>
              </div>

              <div className="flex gap-4">
                <div className="w-10 h-10 bg-green-100 dark:bg-green-900 rounded-full flex items-center justify-center flex-shrink-0">
                  <span className="text-green-600 dark:text-green-300 font-bold">4</span>
                </div>
                <div>
                  <h4 className="font-semibold mb-2 dark:text-white">Grant permissions</h4>
                  <p className="text-slate-600 dark:text-slate-300">Follow the prompts and grant necessary permissions (SMS, Contacts, Phone) for full functionality.</p>
                </div>
              </div>
            </div>

            <div className="mt-6 p-4 bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg">
              <div className="flex gap-3">
                <svg className="w-6 h-6 text-blue-600 dark:text-blue-400 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                <div>
                  <h5 className="font-semibold text-blue-900 dark:text-blue-200 mb-1">Verify SHA-256 Checksum</h5>
                  <p className="text-sm text-blue-800 dark:text-blue-300">
                    For added security, verify the downloaded file matches the SHA-256 checksum shown above. This ensures the file hasn't been tampered with.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* System Requirements */}
        <div className="bg-white dark:bg-slate-900 rounded-2xl p-8 mb-12 shadow-lg">
          <h2 className="text-2xl font-bold mb-6 dark:text-white">System Requirements</h2>

          <div className="grid md:grid-cols-2 gap-6">
            <div>
              <h3 className="font-semibold mb-3 text-blue-600 dark:text-blue-400">Mac</h3>
              <ul className="space-y-2 text-slate-600 dark:text-slate-300">
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>macOS 13.0 (Ventura) or later</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>Apple Silicon (M1/M2/M3) or Intel processor</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>200 MB free disk space</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>Internet connection</span>
                </li>
              </ul>
            </div>

            <div>
              <h3 className="font-semibold mb-3 text-purple-600 dark:text-purple-400">Android Phone</h3>
              <ul className="space-y-2 text-slate-600 dark:text-slate-300">
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>Android 8.0 or later</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>SyncFlow Android app installed</span>
                </li>
                <li className="flex items-start gap-2">
                  <svg className="w-5 h-5 text-green-500 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                  </svg>
                  <span>Same Wi-Fi network for pairing</span>
                </li>
              </ul>
            </div>
          </div>
        </div>

        {/* FAQs */}
        <div className="bg-white dark:bg-slate-900 rounded-2xl p-8 shadow-lg">
          <h2 className="text-2xl font-bold mb-6 dark:text-white">Frequently Asked Questions</h2>

          <div className="space-y-6">
            <div>
              <h3 className="font-semibold mb-2 dark:text-white">Is SyncFlow safe to use?</h3>
              <p className="text-slate-600 dark:text-slate-300">
                Yes! SyncFlow is code-signed and notarized by Apple, which means it has been scanned for malware and verified by Apple. All data is encrypted end-to-end.
              </p>
            </div>

            <div>
              <h3 className="font-semibold mb-2 dark:text-white">Do I need a subscription?</h3>
              <p className="text-slate-600 dark:text-slate-300">
                SyncFlow offers a free tier with basic features. Premium features like photo sync and increased storage require a subscription ($4.99/month).
              </p>
            </div>

            <div>
              <h3 className="font-semibold mb-2 dark:text-white">How do I uninstall SyncFlow?</h3>
              <p className="text-slate-600 dark:text-slate-300">
                Simply drag the SyncFlow app from your Applications folder to the Trash, then empty the Trash.
              </p>
            </div>

            <div>
              <h3 className="font-semibold mb-2 dark:text-white">Where can I get support?</h3>
              <p className="text-slate-600 dark:text-slate-300">
                Visit our <Link href="/support" className="text-blue-600 hover:underline">support page</Link> to submit a support request.
              </p>
            </div>

            <div>
              <h3 className="font-semibold mb-2 dark:text-white">What is the SHA-256 checksum for?</h3>
              <p className="text-slate-600 dark:text-slate-300">
                The SHA-256 checksum is a unique fingerprint of the downloaded file. You can verify this checksum to ensure the file hasn't been modified or corrupted during download, providing an extra layer of security.
              </p>
            </div>
          </div>
        </div>

        {/* Links */}
        <div className="text-center mt-12 text-sm text-slate-500 dark:text-slate-400">
          <Link href="/privacy" className="hover:text-blue-600 mx-3">Privacy Policy</Link>
          <span>•</span>
          <Link href="/terms" className="hover:text-blue-600 mx-3">Terms of Service</Link>
          <span>•</span>
          <Link href="/support" className="hover:text-blue-600 mx-3">Support</Link>
        </div>
      </main>

      {/* Footer */}
      <footer className="border-t border-slate-200 dark:border-slate-700 mt-16">
        <div className="max-w-6xl mx-auto px-4 py-8 text-center text-slate-600 dark:text-slate-400">
          <p>&copy; 2026 SyncFlow. All rights reserved.</p>
        </div>
      </footer>
    </div>
  )
}
