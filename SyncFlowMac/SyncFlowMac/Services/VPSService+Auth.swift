/**
 * VPSService+Auth - Authentication, pairing, and QR code generation
 *
 * Contains:
 * - Device pairing initiation and redemption
 * - Pairing status polling
 * - Token refresh
 * - Current user retrieval
 * - QR code data generation for pairing flow
 * - Pairing approval polling with timeout
 */

import Foundation

extension VPSService {

    // MARK: - Authentication

    public func initiatePairing(deviceName: String) async throws -> VPSPairingRequest {
        var body: [String: Any] = [
            "deviceName": deviceName,
            "deviceType": "macos"
        ]
        // Include signing key if available
        if let signingKey = E2EEManager.shared.getSigningPublicKeyX963Base64() {
            body["signingKey"] = signingKey
        }

        let response: VPSPairingRequest = try await post("/api/auth/pair/initiate", body: body, skipAuth: true)

        // Save temporary tokens
        saveTokens(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken,
            userId: response.tempUserId,
            deviceId: response.deviceId
        )

        #if DEBUG
        print("[VPS] Pairing initiated")
        #endif
        return response
    }

    public func checkPairingStatus(token: String) async throws -> VPSPairingStatus {
        guard let url = URL(string: "\(baseUrl)/api/auth/pair/status/\(token)") else {
            throw VPSError.invalidResponse
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw VPSError.invalidResponse
        }

        if httpResponse.statusCode == 404 {
            throw VPSError.pairingExpired
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            throw VPSError.httpError(httpResponse.statusCode, nil)
        }

        return try decoder.decode(VPSPairingStatus.self, from: data)
    }

    public func redeemPairing(token: String) async throws -> VPSUser {
        // Include tempUserId so server can clean up the orphaned anonymous user
        var body: [String: Any] = [
            "token": token,
            "deviceName": Host.current().localizedName ?? "Mac",
            "deviceType": "macos"
        ]
        if let currentUserId = userId {
            body["tempUserId"] = currentUserId
        }
        // Include signing key if available
        if let signingKey = E2EEManager.shared.getSigningPublicKeyX963Base64() {
            body["signingKey"] = signingKey
        }

        let response: VPSAuthResponse = try await post("/api/auth/pair/redeem", body: body, skipAuth: true)

        saveTokens(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken,
            userId: response.userId,
            deviceId: response.deviceId
        )

        #if DEBUG
        print("[VPS] Pairing redeemed successfully")
        #endif

        // Connect WebSocket after successful pairing
        connectWebSocket()

        return VPSUser(userId: response.userId, deviceId: response.deviceId, admin: false)
    }

    // refreshAccessToken() is defined in VPSService.swift (core token management)

    public func getCurrentUser() async throws -> VPSUser {
        return try await get("/api/auth/me")
    }

    // MARK: - Pairing QR Code

    public func generatePairingQRData() async throws -> (token: String, qrData: String) {
        let deviceName = Host.current().localizedName ?? "Mac"
        let pairing = try await initiatePairing(deviceName: deviceName)

        // Get macOS E2EE public key to include in QR code
        // This allows Android to encrypt the sync group keys for macOS
        // Build QR data with URLComponents to ensure proper encoding (+ / =)
        var components = URLComponents()
        components.scheme = "syncflow"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "token", value: pairing.pairingToken),
            URLQueryItem(name: "server", value: baseUrl),
            URLQueryItem(name: "deviceId", value: pairing.deviceId)
        ]

        // Include E2EE public key if available so Android can encrypt
        // the sync group keys for this macOS device during pairing
        try? await E2EEManager.shared.initializeKeys()
        if let publicKeyX963 = E2EEManager.shared.getMyPublicKeyX963Base64() {
            components.queryItems?.append(URLQueryItem(name: "e2eeKey", value: publicKeyX963))
            #if DEBUG
            print("[VPS] Including E2EE public key in QR code")
            #endif
        } else {
            #if DEBUG
            print("[VPS] Warning: No E2EE public key available for QR code")
            #endif
        }

        let qrData = components.url?.absoluteString
            ?? "syncflow://pair?token=\(pairing.pairingToken)&server=\(baseUrl)&deviceId=\(pairing.deviceId)"

        return (pairing.pairingToken, qrData)
    }

    public func waitForPairingApproval(token: String, timeout: TimeInterval = 300) async throws -> VPSUser {
        let startTime = Date()

        while Date().timeIntervalSince(startTime) < timeout {
            do {
                let status = try await checkPairingStatus(token: token)

                if status.approved {
                    // Pairing approved, redeem it
                    return try await redeemPairing(token: token)
                }

                if status.status == "expired" {
                    throw VPSError.pairingExpired
                }

                // Wait before checking again
                try await Task.sleep(nanoseconds: 2_000_000_000) // 2 seconds

            } catch VPSError.pairingExpired {
                throw VPSError.pairingExpired
            } catch {
                // Continue polling on other errors
                try await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }

        throw VPSError.pairingExpired
    }
}
