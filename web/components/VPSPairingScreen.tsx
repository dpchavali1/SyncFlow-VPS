'use client';

/**
 * VPS Pairing Screen
 *
 * Displays a QR code for pairing with Android device via VPS server.
 * This replaces the Firebase-based pairing flow.
 */

import React, { useState, useEffect, useCallback } from 'react';
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
      onPairingComplete?.(user);

    } catch (err: any) {
      setIsLoading(false);
      setIsPairing(false);
      setError(err.message || 'Failed to start pairing');
      onError?.(err);
    }
  }, [onPairingComplete, onError]);

  useEffect(() => {
    startPairing();
  }, [startPairing]);

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-gray-50 dark:bg-gray-900 p-4">
      <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-xl p-8 max-w-md w-full">
        {/* Header */}
        <div className="text-center mb-6">
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white mb-2">
            Pair with Android Phone
          </h1>
          <p className="text-gray-600 dark:text-gray-400">
            Scan this QR code with your SyncFlow Android app to connect
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

        {/* Server Info */}
        <div className="mt-4 text-center">
          <p className="text-xs text-gray-400">
            Server: {process.env.NEXT_PUBLIC_VPS_URL || 'http://5.78.188.206'}
          </p>
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
