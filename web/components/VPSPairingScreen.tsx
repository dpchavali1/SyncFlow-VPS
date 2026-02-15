'use client';

/**
 * VPS Pairing Screen
 *
 * Displays a QR code for pairing with Android device via VPS server.
 * This replaces the Firebase-based pairing flow.
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import QRCode from 'qrcode';
import vpsService, { VPSUser } from '@/lib/vps';

interface VPSPairingScreenProps {
  onPairingComplete?: (user: VPSUser) => void;
  onError?: (error: Error) => void;
}

export default function VPSPairingScreen({ onPairingComplete, onError }: VPSPairingScreenProps) {
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [pairingToken, setPairingToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isPairing, setIsPairing] = useState(false);
  const [isPaired, setIsPaired] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Use refs for callbacks to avoid re-triggering startPairing when parent re-renders
  const onPairingCompleteRef = useRef(onPairingComplete);
  onPairingCompleteRef.current = onPairingComplete;
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;

  const startPairing = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    setIsPairing(false);
    setIsPaired(false);

    try {
      const { token, qrData } = await vpsService.generatePairingQRData();
      setPairingToken(token);

      // Generate QR code image
      const dataUrl = await QRCode.toDataURL(qrData, {
        width: 256,
        margin: 2,
        color: {
          dark: '#000000',
          light: '#ffffff',
        },
      });
      setQrDataUrl(dataUrl);
      setIsLoading(false);
      setIsPairing(true);

      // Start polling for approval
      const user = await vpsService.waitForPairingApproval(token);
      setIsPairing(false);
      setIsPaired(true);
      onPairingCompleteRef.current?.(user);

    } catch (err: any) {
      setIsLoading(false);
      setIsPairing(false);
      setError(err.message || 'Failed to start pairing');
      onErrorRef.current?.(err);
    }
  }, []);

  useEffect(() => {
    startPairing();
  }, [startPairing]);

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-gray-50 dark:bg-gray-900 p-4">
      {/* Page Title */}
      <div className="text-center mb-8">
        <h1 className="text-4xl font-bold bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-transparent mb-2">
          SyncFlow
        </h1>
        <p className="text-gray-600 dark:text-gray-400">
          Seamlessly sync your Android phone with Mac &amp; Web
        </p>
      </div>

      <div className="flex flex-col lg:flex-row items-start justify-center gap-6 w-full max-w-5xl">
        {/* QR Code Pairing Card */}
        <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-xl p-8 w-full lg:w-auto lg:min-w-[400px]">
          {/* Header */}
          <div className="text-center mb-6">
            <h2 className="text-2xl font-bold text-gray-900 dark:text-white mb-2">
              Pair with Android Phone
            </h2>
            <p className="text-gray-600 dark:text-gray-400 text-sm">
              Scan this QR code with your SyncFlow Android app
            </p>
          </div>

          {/* QR Code */}
          <div className="flex justify-center mb-6">
            <div className="bg-white p-4 rounded-xl shadow-inner">
              {isLoading ? (
                <div className="w-64 h-64 flex items-center justify-center">
                  <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-500"></div>
                </div>
              ) : error ? (
                <div className="w-64 h-64 flex flex-col items-center justify-center text-center">
                  <svg
                    className="w-16 h-16 text-orange-500 mb-4"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
                    />
                  </svg>
                  <p className="text-gray-600 dark:text-gray-400 text-sm mb-4">{error}</p>
                  <button
                    onClick={startPairing}
                    className="px-4 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600 transition-colors"
                  >
                    Retry
                  </button>
                </div>
              ) : qrDataUrl ? (
                <img src={qrDataUrl} alt="Pairing QR Code" className="w-64 h-64" />
              ) : null}
            </div>
          </div>

          {/* Status */}
          <div className="flex items-center justify-center mb-6">
            {isPairing && (
              <>
                <div className="w-2 h-2 bg-orange-500 rounded-full mr-2 animate-pulse"></div>
                <span className="text-gray-600 dark:text-gray-400 text-sm">
                  Waiting for phone to scan...
                </span>
              </>
            )}
            {isPaired && (
              <>
                <div className="w-2 h-2 bg-green-500 rounded-full mr-2"></div>
                <span className="text-green-600 text-sm font-medium">Connected!</span>
              </>
            )}
          </div>

          {/* Instructions */}
          <div className="bg-gray-50 dark:bg-gray-700 rounded-xl p-4">
            <div className="space-y-3">
              <InstructionStep number={1} text="Open SyncFlow on your Android phone" />
              <InstructionStep number={2} text="Go to Settings → Pair Device" />
              <InstructionStep number={3} text="Tap 'Scan QR Code' and scan this code" />
            </div>
          </div>

          {/* Server Info - only show in development */}
          {process.env.NODE_ENV === 'development' && (
            <div className="mt-4 text-center">
              <p className="text-xs text-gray-400">
                Server: {process.env.NEXT_PUBLIC_VPS_URL || 'localhost'}
              </p>
            </div>
          )}
        </div>

        {/* Get the Apps Card */}
        <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-xl p-8 w-full lg:w-auto lg:min-w-[360px]">
          <h2 className="text-xl font-bold text-gray-900 dark:text-white mb-6 text-center">
            Get the Apps
          </h2>

          {/* macOS App */}
          <div className="mb-6">
            <div className="flex items-center gap-3 mb-3">
              <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-purple-600 rounded-xl flex items-center justify-center flex-shrink-0">
                <svg className="w-5 h-5 text-white" fill="currentColor" viewBox="0 0 24 24">
                  <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z"/>
                </svg>
              </div>
              <div>
                <h3 className="font-semibold text-gray-900 dark:text-white">SyncFlow for Mac</h3>
                <p className="text-xs text-gray-500 dark:text-gray-400">macOS 13.0+ &bull; Apple Silicon &amp; Intel</p>
              </div>
            </div>
            <div className="space-y-2 ml-13 text-sm text-gray-600 dark:text-gray-300">
              <div className="flex items-start gap-2">
                <span className="text-blue-500 mt-0.5">1.</span>
                <span>Download the DMG from the <a href="/download" className="text-blue-500 hover:underline">downloads page</a></span>
              </div>
              <div className="flex items-start gap-2">
                <span className="text-blue-500 mt-0.5">2.</span>
                <span>Open the DMG and drag SyncFlow to Applications</span>
              </div>
              <div className="flex items-start gap-2">
                <span className="text-blue-500 mt-0.5">3.</span>
                <span>Launch SyncFlow &mdash; a QR code will appear to pair</span>
              </div>
            </div>
            <div className="mt-3 p-2 bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 rounded-lg">
              <p className="text-xs text-yellow-700 dark:text-yellow-300">
                <strong>First launch:</strong> Right-click the app and select &quot;Open&quot; to bypass macOS Gatekeeper.
              </p>
            </div>
          </div>

          <hr className="border-gray-200 dark:border-gray-700 mb-6" />

          {/* Android App */}
          <div className="mb-6">
            <div className="flex items-center gap-3 mb-3">
              <div className="w-10 h-10 bg-gradient-to-br from-green-500 to-teal-600 rounded-xl flex items-center justify-center flex-shrink-0">
                <svg className="w-5 h-5 text-white" fill="currentColor" viewBox="0 0 24 24">
                  <path d="M17.523 15.3414c-.5511 0-.9993-.4486-.9993-.9997s.4483-.9993.9993-.9993c.5511 0 .9993.4483.9993.9993.0001.5511-.4482.9997-.9993.9997m-11.046 0c-.5511 0-.9993-.4486-.9993-.9997s.4482-.9993.9993-.9993c.5511 0 .9993.4483.9993.9993 0 .5511-.4483.9997-.9993.9997m11.4045-6.02l1.9973-3.4592a.416.416 0 00-.1521-.5676.416.416 0 00-.5676.1521l-2.0223 3.503C15.5902 8.2439 13.8533 7.8508 12 7.8508s-3.5902.3931-5.1367 1.0989L4.841 5.4467a.4161.4161 0 00-.5677-.1521.4157.4157 0 00-.1521.5676l1.9973 3.4592C2.6889 11.1867.3432 14.6589 0 18.761h24c-.3435-4.1021-2.6892-7.5743-6.1185-9.4396"/>
                </svg>
              </div>
              <div>
                <h3 className="font-semibold text-gray-900 dark:text-white">SyncFlow for Android</h3>
                <p className="text-xs text-gray-500 dark:text-gray-400">Android 8.0+ &bull; All devices</p>
              </div>
            </div>
            <div className="space-y-2 ml-13 text-sm text-gray-600 dark:text-gray-300">
              <div className="flex items-start gap-2">
                <span className="text-green-500 mt-0.5">1.</span>
                <span>Download the APK from the <a href="/download" className="text-blue-500 hover:underline">downloads page</a></span>
              </div>
              <div className="flex items-start gap-2">
                <span className="text-green-500 mt-0.5">2.</span>
                <span>Enable &quot;Install from unknown sources&quot; if prompted</span>
              </div>
              <div className="flex items-start gap-2">
                <span className="text-green-500 mt-0.5">3.</span>
                <span>Open the app, grant permissions, then scan the QR code</span>
              </div>
            </div>
          </div>

          <hr className="border-gray-200 dark:border-gray-700 mb-6" />

          {/* Web App (already here) */}
          <div>
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 bg-gradient-to-br from-indigo-500 to-blue-600 rounded-xl flex items-center justify-center flex-shrink-0">
                <svg className="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" />
                </svg>
              </div>
              <div>
                <h3 className="font-semibold text-gray-900 dark:text-white">SyncFlow Web</h3>
                <p className="text-xs text-gray-500 dark:text-gray-400">Any browser &bull; No install needed</p>
              </div>
            </div>
            <p className="text-sm text-gray-600 dark:text-gray-300 ml-13">
              You&apos;re already here! Scan the QR code with your paired Android phone to get started.
            </p>
          </div>

          {/* Features Summary */}
          <div className="mt-6 bg-gray-50 dark:bg-gray-700 rounded-xl p-4">
            <h4 className="text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-3">What you get</h4>
            <div className="grid grid-cols-2 gap-2 text-xs text-gray-600 dark:text-gray-300">
              <div className="flex items-center gap-1.5">
                <svg className="w-3.5 h-3.5 text-green-500 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                </svg>
                SMS &amp; MMS sync
              </div>
              <div className="flex items-center gap-1.5">
                <svg className="w-3.5 h-3.5 text-green-500 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                </svg>
                Call history
              </div>
              <div className="flex items-center gap-1.5">
                <svg className="w-3.5 h-3.5 text-green-500 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                </svg>
                Contacts sync
              </div>
              <div className="flex items-center gap-1.5">
                <svg className="w-3.5 h-3.5 text-green-500 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                </svg>
                File transfer
              </div>
              <div className="flex items-center gap-1.5">
                <svg className="w-3.5 h-3.5 text-green-500 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                </svg>
                E2E encrypted
              </div>
              <div className="flex items-center gap-1.5">
                <svg className="w-3.5 h-3.5 text-green-500 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                </svg>
                Photo sync
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function InstructionStep({ number, text }: { number: number; text: string }) {
  return (
    <div className="flex items-start">
      <div className="flex-shrink-0 w-6 h-6 bg-blue-500 rounded-full flex items-center justify-center mr-3">
        <span className="text-white text-xs font-bold">{number}</span>
      </div>
      <p className="text-gray-700 dark:text-gray-300 text-sm">{text}</p>
    </div>
  );
}
