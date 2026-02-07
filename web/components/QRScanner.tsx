'use client'

import { useEffect, useRef, useState } from 'react'
import { Camera, AlertCircle } from 'lucide-react'

interface QRScannerProps {
  onScanComplete: (token: string) => void
}

export default function QRScanner({ onScanComplete }: QRScannerProps) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [error, setError] = useState<string>('')
  const [hasPermission, setHasPermission] = useState<boolean>(false)

  useEffect(() => {
    let stream: MediaStream | null = null
    let animationId: number

    const startCamera = async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment' },
        })

        if (videoRef.current) {
          videoRef.current.srcObject = stream
          setHasPermission(true)
          scanQRCode()
        }
      } catch (err) {
        setError('Camera permission denied. Please allow camera access to scan QR codes.')
        console.error('Camera error:', err)
      }
    }

    const scanQRCode = () => {
      if (!videoRef.current || !canvasRef.current) return

      const video = videoRef.current
      const canvas = canvasRef.current
      const ctx = canvas.getContext('2d')

      if (!ctx) return

      const scan = () => {
        if (video.readyState === video.HAVE_ENOUGH_DATA) {
          canvas.width = video.videoWidth
          canvas.height = video.videoHeight

          ctx.drawImage(video, 0, 0, canvas.width, canvas.height)

          const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height)

          // Try to decode QR code from imageData
          // Note: In production, you'd use a library like jsQR here
          // For now, we'll use a simple approach with manual input fallback
        }

        animationId = requestAnimationFrame(scan)
      }

      scan()
    }

    startCamera()

    return () => {
      if (stream) {
        stream.getTracks().forEach((track) => track.stop())
      }
      if (animationId) {
        cancelAnimationFrame(animationId)
      }
    }
  }, [])

  const [manualToken, setManualToken] = useState('')

  const handleManualSubmit = () => {
    if (manualToken) {
      onScanComplete(manualToken)
    }
  }

  return (
    <div className="space-y-4">
      <div className="relative bg-gray-900 rounded-lg overflow-hidden aspect-video">
        {error ? (
          <div className="flex flex-col items-center justify-center h-full p-6 text-center">
            <AlertCircle className="w-12 h-12 text-red-500 mb-3" />
            <p className="text-white text-sm">{error}</p>
          </div>
        ) : (
          <>
            <video
              ref={videoRef}
              autoPlay
              playsInline
              muted
              className="w-full h-full object-cover"
            />
            <canvas ref={canvasRef} className="hidden" />

            {/* Scanning overlay */}
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="border-4 border-blue-500 rounded-lg w-64 h-64 relative">
                <div className="absolute top-0 left-0 w-8 h-8 border-t-4 border-l-4 border-white rounded-tl-lg"></div>
                <div className="absolute top-0 right-0 w-8 h-8 border-t-4 border-r-4 border-white rounded-tr-lg"></div>
                <div className="absolute bottom-0 left-0 w-8 h-8 border-b-4 border-l-4 border-white rounded-bl-lg"></div>
                <div className="absolute bottom-0 right-0 w-8 h-8 border-b-4 border-r-4 border-white rounded-br-lg"></div>
              </div>
            </div>

            {/* Instructions */}
            <div className="absolute bottom-0 left-0 right-0 bg-black bg-opacity-50 text-white p-4 text-center">
              <Camera className="w-6 h-6 mx-auto mb-2" />
              <p className="text-sm">Position the QR code within the frame</p>
            </div>
          </>
        )}
      </div>

      {/* Manual input fallback */}
      <div className="border-t border-gray-200 dark:border-gray-700 pt-4">
        <p className="text-sm text-gray-600 dark:text-gray-400 mb-2 text-center">
          Or enter pairing code manually:
        </p>
        <div className="flex gap-2">
          <input
            type="text"
            value={manualToken}
            onChange={(e) => setManualToken(e.target.value)}
            placeholder="Paste pairing code here"
            className="flex-1 px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
          <button
            onClick={handleManualSubmit}
            disabled={!manualToken}
            className="bg-blue-600 hover:bg-blue-700 disabled:bg-gray-400 text-white font-semibold py-2 px-6 rounded-lg transition-colors"
          >
            Pair
          </button>
        </div>
      </div>
    </div>
  )
}
