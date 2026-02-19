'use client';

/**
 * VPS Pairing Screen
 *
 * Displays a QR code for pairing with Android device via VPS server.
 * This replaces the Firebase-based pairing flow.
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Smartphone, Monitor, Globe, Check, Download, Shield, MessageSquare, Phone, Image, FileText, RefreshCw, AlertTriangle } from 'lucide-react';
import QRCode from 'qrcode';
import vpsService, { VPSUser } from '@/lib/vps';
import { scaleIn, fadeIn } from '@/lib/animations';
import Link from 'next/link';

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
        width: 280,
        margin: 2,
        color: {
          dark: '#0f172a',
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
    <div className="flex flex-col items-center justify-center min-h-screen bg-mesh p-4 overflow-y-auto">
      {/* Page Title */}
      <motion.div
        variants={fadeIn}
        initial="hidden"
        animate="visible"
        className="text-center mb-10"
      >
        <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-gradient-to-br from-blue-500 to-violet-600 shadow-lg shadow-blue-500/25 mb-4">
          <MessageSquare className="w-8 h-8 text-white" />
        </div>
        <h1 className="text-4xl font-bold text-gradient mb-2">
          SyncFlow
        </h1>
        <p className="text-gray-500 dark:text-gray-400 text-sm">
          Seamlessly sync your Android phone with Mac &amp; Web
        </p>
      </motion.div>

      <div className="flex flex-col lg:flex-row items-start justify-center gap-6 w-full max-w-5xl">
        {/* QR Code Pairing Card */}
        <motion.div
          variants={scaleIn}
          initial="hidden"
          animate="visible"
          className="glass-elevated rounded-3xl p-8 w-full lg:w-auto lg:min-w-[420px]"
        >
          {/* Header */}
          <div className="text-center mb-6">
            <h2 className="text-2xl font-bold text-gray-900 dark:text-white mb-2">
              Pair with Android Phone
            </h2>
            <p className="text-gray-500 dark:text-gray-400 text-sm">
              Scan this QR code with your SyncFlow Android app
            </p>
          </div>

          {/* QR Code */}
          <div className="flex justify-center mb-6">
            <div className="bg-white rounded-2xl p-5 shadow-lg">
              <AnimatePresence mode="wait">
                {isLoading ? (
                  <motion.div
                    key="loading"
                    variants={fadeIn}
                    initial="hidden"
                    animate="visible"
                    exit="exit"
                    className="w-64 h-64 flex flex-col items-center justify-center"
                  >
                    <div className="relative">
                      <div className="w-12 h-12 rounded-full border-2 border-blue-200 border-t-blue-500 animate-spin" />
                    </div>
                    <p className="text-sm text-gray-400 mt-4">Generating QR code...</p>
                  </motion.div>
                ) : error ? (
                  <motion.div
                    key="error"
                    variants={scaleIn}
                    initial="hidden"
                    animate="visible"
                    exit="exit"
                    className="w-64 h-64 flex flex-col items-center justify-center text-center"
                  >
                    <div className="w-14 h-14 rounded-2xl bg-amber-500/10 flex items-center justify-center mb-4">
                      <AlertTriangle className="w-7 h-7 text-amber-500" />
                    </div>
                    <p className="text-gray-600 dark:text-gray-400 text-sm mb-4">{error}</p>
                    <motion.button
                      whileHover={{ scale: 1.03 }}
                      whileTap={{ scale: 0.97 }}
                      onClick={startPairing}
                      className="px-5 py-2.5 bg-gradient-to-r from-blue-500 to-blue-600 text-white text-sm font-medium rounded-xl shadow-md shadow-blue-500/20 hover:shadow-lg transition-shadow"
                    >
                      <RefreshCw className="w-4 h-4 inline mr-2" />
                      Retry
                    </motion.button>
                  </motion.div>
                ) : isPaired ? (
                  <motion.div
                    key="paired"
                    variants={scaleIn}
                    initial="hidden"
                    animate="visible"
                    className="w-64 h-64 flex flex-col items-center justify-center"
                  >
                    <div className="w-16 h-16 rounded-full bg-emerald-500 flex items-center justify-center mb-4 shadow-lg shadow-emerald-500/30">
                      <Check className="w-8 h-8 text-white" />
                    </div>
                    <p className="text-lg font-semibold text-gray-900 dark:text-white">Connected!</p>
                    <p className="text-sm text-gray-400 mt-1">Redirecting...</p>
                  </motion.div>
                ) : qrDataUrl ? (
                  <motion.img
                    key="qr"
                    variants={scaleIn}
                    initial="hidden"
                    animate="visible"
                    src={qrDataUrl}
                    alt="Pairing QR Code"
                    className="w-64 h-64 rounded-lg"
                  />
                ) : null}
              </AnimatePresence>
            </div>
          </div>

          {/* Status */}
          <div className="flex items-center justify-center mb-6 h-6">
            <AnimatePresence mode="wait">
              {isPairing && (
                <motion.div
                  key="pairing"
                  variants={fadeIn}
                  initial="hidden"
                  animate="visible"
                  exit="exit"
                  className="flex items-center"
                >
                  <div className="w-2 h-2 bg-amber-500 rounded-full mr-2 animate-pulse" />
                  <span className="text-gray-500 dark:text-gray-400 text-sm">
                    Waiting for phone to scan...
                  </span>
                </motion.div>
              )}
              {isPaired && (
                <motion.div
                  key="paired-status"
                  variants={fadeIn}
                  initial="hidden"
                  animate="visible"
                  exit="exit"
                  className="flex items-center"
                >
                  <div className="w-2 h-2 bg-emerald-500 rounded-full mr-2" />
                  <span className="text-emerald-600 text-sm font-medium">Connected!</span>
                </motion.div>
              )}
            </AnimatePresence>
          </div>

          {/* Instructions */}
          <div className="glass-panel rounded-2xl p-4">
            <div className="space-y-3">
              <InstructionStep number={1} text="Open SyncFlow on your Android phone" />
              <InstructionStep number={2} text="Go to Settings → Pair Device" />
              <InstructionStep number={3} text="Tap 'Scan QR Code' and scan this code" />
            </div>
          </div>

          {/* Server Info - only show in development */}
          {process.env.NODE_ENV === 'development' && (
            <div className="mt-4 text-center">
              <p className="text-[11px] text-gray-400">
                Server: {process.env.NEXT_PUBLIC_VPS_URL || 'localhost'}
              </p>
            </div>
          )}
        </motion.div>

        {/* Get the Apps Card */}
        <motion.div
          variants={scaleIn}
          initial="hidden"
          animate="visible"
          transition={{ delay: 0.1 }}
          className="glass-elevated rounded-3xl p-8 w-full lg:w-auto lg:min-w-[360px]"
        >
          <h2 className="text-xl font-bold text-gray-900 dark:text-white mb-6 text-center">
            Get the Apps
          </h2>

          {/* macOS App */}
          <div className="mb-6">
            <div className="flex items-center gap-3 mb-3">
              <div className="w-10 h-10 bg-gradient-to-br from-blue-500 to-purple-600 rounded-xl flex items-center justify-center flex-shrink-0 shadow-md shadow-blue-500/20">
                <Monitor className="w-5 h-5 text-white" />
              </div>
              <div>
                <h3 className="font-semibold text-gray-900 dark:text-white">SyncFlow for Mac</h3>
                <p className="text-[11px] text-gray-400 dark:text-gray-500">macOS 13.0+ &bull; Apple Silicon &amp; Intel</p>
              </div>
            </div>
            <div className="space-y-2 ml-13 text-sm text-gray-600 dark:text-gray-300">
              <div className="flex items-start gap-2">
                <span className="text-blue-500 mt-0.5 font-medium">1.</span>
                <span>Download the DMG from the <Link href="/download" className="text-blue-500 hover:text-blue-600 underline underline-offset-2">downloads page</Link></span>
              </div>
              <div className="flex items-start gap-2">
                <span className="text-blue-500 mt-0.5 font-medium">2.</span>
                <span>Open the DMG and drag SyncFlow to Applications</span>
              </div>
              <div className="flex items-start gap-2">
                <span className="text-blue-500 mt-0.5 font-medium">3.</span>
                <span>Launch SyncFlow &mdash; a QR code will appear to pair</span>
              </div>
            </div>
            <div className="mt-3 p-2.5 bg-amber-500/10 border border-amber-200/30 dark:border-amber-700/30 rounded-xl">
              <p className="text-[11px] text-amber-700 dark:text-amber-300">
                <strong>First launch:</strong> Right-click the app and select &quot;Open&quot; to bypass macOS Gatekeeper.
              </p>
            </div>
          </div>

          <div className="border-t border-gray-200/50 dark:border-gray-700/50 my-6" />

          {/* Android App */}
          <div className="mb-6">
            <div className="flex items-center gap-3 mb-3">
              <div className="w-10 h-10 bg-gradient-to-br from-emerald-500 to-teal-600 rounded-xl flex items-center justify-center flex-shrink-0 shadow-md shadow-emerald-500/20">
                <Smartphone className="w-5 h-5 text-white" />
              </div>
              <div>
                <h3 className="font-semibold text-gray-900 dark:text-white">SyncFlow for Android</h3>
                <p className="text-[11px] text-gray-400 dark:text-gray-500">Android 8.0+ &bull; All devices</p>
              </div>
            </div>
            <div className="space-y-2 ml-13 text-sm text-gray-600 dark:text-gray-300">
              <div className="flex items-start gap-2">
                <span className="text-emerald-500 mt-0.5 font-medium">1.</span>
                <span>Download the APK from the <Link href="/download" className="text-blue-500 hover:text-blue-600 underline underline-offset-2">downloads page</Link></span>
              </div>
              <div className="flex items-start gap-2">
                <span className="text-emerald-500 mt-0.5 font-medium">2.</span>
                <span>Enable &quot;Install from unknown sources&quot; if prompted</span>
              </div>
              <div className="flex items-start gap-2">
                <span className="text-emerald-500 mt-0.5 font-medium">3.</span>
                <span>Open the app, grant permissions, then scan the QR code</span>
              </div>
            </div>
          </div>

          <div className="border-t border-gray-200/50 dark:border-gray-700/50 my-6" />

          {/* Web App (already here) */}
          <div>
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 bg-gradient-to-br from-indigo-500 to-blue-600 rounded-xl flex items-center justify-center flex-shrink-0 shadow-md shadow-indigo-500/20">
                <Globe className="w-5 h-5 text-white" />
              </div>
              <div>
                <h3 className="font-semibold text-gray-900 dark:text-white">SyncFlow Web</h3>
                <p className="text-[11px] text-gray-400 dark:text-gray-500">Any browser &bull; No install needed</p>
              </div>
            </div>
            <p className="text-sm text-gray-500 dark:text-gray-400 ml-13">
              You&apos;re already here! Scan the QR code with your paired Android phone to get started.
            </p>
          </div>

          {/* Features Summary */}
          <div className="mt-6 glass-panel rounded-2xl p-4">
            <h4 className="text-[11px] font-semibold text-gray-400 dark:text-gray-500 uppercase tracking-wider mb-3">What you get</h4>
            <div className="grid grid-cols-2 gap-2.5 text-xs text-gray-600 dark:text-gray-300">
              <FeatureItem icon={<MessageSquare className="w-3.5 h-3.5" />} text="SMS & MMS sync" color="text-blue-500" />
              <FeatureItem icon={<Phone className="w-3.5 h-3.5" />} text="Call history" color="text-violet-500" />
              <FeatureItem icon={<Smartphone className="w-3.5 h-3.5" />} text="Contacts sync" color="text-cyan-500" />
              <FeatureItem icon={<FileText className="w-3.5 h-3.5" />} text="File transfer" color="text-emerald-500" />
              <FeatureItem icon={<Shield className="w-3.5 h-3.5" />} text="E2E encrypted" color="text-amber-500" />
              <FeatureItem icon={<Image className="w-3.5 h-3.5" />} text="Photo sync" color="text-pink-500" />
            </div>
          </div>
        </motion.div>
      </div>
    </div>
  );
}

function InstructionStep({ number, text }: { number: number; text: string }) {
  return (
    <div className="flex items-start gap-3">
      <div className="flex-shrink-0 w-6 h-6 bg-gradient-to-br from-blue-500 to-blue-600 rounded-lg flex items-center justify-center shadow-sm shadow-blue-500/20">
        <span className="text-white text-[11px] font-bold">{number}</span>
      </div>
      <p className="text-gray-600 dark:text-gray-300 text-sm leading-relaxed">{text}</p>
    </div>
  );
}

function FeatureItem({ icon, text, color }: { icon: React.ReactNode; text: string; color: string }) {
  return (
    <div className="flex items-center gap-2">
      <span className={`${color} flex-shrink-0`}>{icon}</span>
      <span>{text}</span>
    </div>
  );
}
