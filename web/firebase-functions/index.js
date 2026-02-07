// Firebase Cloud Function for pairing token validation
// This runs on Firebase servers to securely validate pairing requests

const functions = require('firebase-functions');
const admin = require('firebase-admin');

admin.initializeApp();

// Cloud Function to initiate device pairing
exports.initiatePairing = functions.https.onCall(async (data, context) => {
  try {
    const { deviceName, platform = 'macos', version, syncGroupId } = data;

    if (!deviceName) {
      throw new functions.https.HttpsError('invalid-argument', 'Missing device name');
    }

    // Generate unique pairing token
    const token = generatePairingToken();
    const currentTime = Date.now();
    const expiresAt = currentTime + (10 * 60 * 1000); // 10 minutes expiration

    // Create pairing session
    const pairingData = {
      token: token,
      deviceName: deviceName,
      platform: platform,
      version: version || '1.0.0',
      status: 'pending',
      createdAt: currentTime,
      expiresAt: expiresAt,
      syncGroupId: syncGroupId || null
    };

    // Store in database
    await admin.database().ref(`pairing_tokens/${token}`).set(pairingData);

    console.log(`Initiated pairing for device: ${deviceName} with token: ${token}`);

    // Create QR payload with token and device info
    const qrPayload = JSON.stringify({
      token: token,
      name: deviceName,
      platform: platform,
      version: version || '1.0.0',
      syncGroupId: syncGroupId
    });

    return {
      success: true,
      token: token,
      qrPayload: qrPayload,
      deviceName: deviceName,
      platform: platform,
      expiresAt: expiresAt,
      syncGroupId: syncGroupId
    };

  } catch (error) {
    console.error('Error initiating pairing:', error);
    throw new functions.https.HttpsError('internal', 'Failed to initiate pairing');
  }
});

// Helper function to generate random pairing token
function generatePairingToken() {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let result = '';
  for (let i = 0; i < 32; i++) {
    result += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return result;
}

// Cloud Function to complete device pairing
exports.completePairing = functions.https.onCall(async (data, context) => {
  try {
    // Validate input
    const { token, approved } = data;

    if (!token || typeof approved !== 'boolean') {
      throw new functions.https.HttpsError('invalid-argument', 'Missing token or approval status');
    }

    // Get token data
    const tokenRef = admin.database().ref(`pairing_tokens/${token}`);
    const tokenSnapshot = await tokenRef.once('value');

    if (!tokenSnapshot.exists()) {
      throw new functions.https.HttpsError('not-found', 'Invalid or expired pairing token');
    }

    const tokenData = tokenSnapshot.val();
    const currentTime = Date.now();

    // Validate token expiry
    if (currentTime > tokenData.expiresAt) {
      // Clean up expired token
      await tokenRef.remove();
      throw new functions.https.HttpsError('deadline-exceeded', 'Pairing token has expired');
    }

    // Check token status
    if (tokenData.status !== 'pending') {
      throw new functions.https.HttpsError('failed-precondition', 'Token has already been used');
    }

    if (approved) {
      // Generate custom token for the desktop app
      const customToken = await admin.auth().createCustomToken('desktop_' + tokenData.deviceName.replace(/\s+/g, '_'));
      
      // Create device ID
      const deviceId = 'macos_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
      
      // Move to pending_pairings with custom token for desktop app to consume
      await admin.database().ref(`pending_pairings/${token}`).set({
        status: 'approved',
        customToken: customToken,
        pairedUid: context.auth?.uid || 'anonymous',
        deviceId: deviceId,
        deviceName: tokenData.deviceName,
        platform: tokenData.platform,
        completedAt: currentTime,
        expiresAt: currentTime + (5 * 60 * 1000), // 5 minutes for desktop to claim
        userId: context.auth?.uid || 'anonymous'
      });

      // Register device under user
      await admin.database().ref(`users/${context.auth?.uid || 'anonymous'}/devices/${deviceId}`).set({
        name: tokenData.deviceName,
        platform: tokenData.platform,
        pairedAt: currentTime,
        status: 'active',
        lastSeen: currentTime
      });

      // Clean up original token
      await tokenRef.remove();

      return {
        success: true,
        status: 'approved',
        deviceId: deviceId,
        userId: context.auth?.uid || 'anonymous',
        deviceName: tokenData.deviceName,
        customToken: customToken
      };
    } else {
      // Rejected pairing
      await tokenRef.update({
        status: 'rejected',
        completedAt: currentTime,
        approvedBy: context.auth?.uid || 'anonymous'
      });

      return {
        success: true,
        status: 'rejected',
        deviceName: tokenData.deviceName
      };
    }

  } catch (error) {
    console.error('Error completing pairing:', error);
    throw new functions.https.HttpsError('internal', 'Failed to complete pairing');
  }
});

// Cloud Function to validate unified user access
exports.validateUnifiedAccess = functions.https.onCall(async (data, context) => {
  try {
    const { deviceId } = data;
    const userId = context.auth?.uid;

    if (!userId) {
      throw new functions.https.HttpsError('unauthenticated', 'User must be authenticated');
    }

    if (!deviceId) {
      throw new functions.https.HttpsError('invalid-argument', 'Missing device ID');
    }

    // Check if device is registered under this user
    const deviceRef = admin.database().ref(`users/${userId}/devices/${deviceId}`);
    const deviceSnapshot = await deviceRef.once('value');

    if (!deviceSnapshot.exists()) {
      throw new functions.https.HttpsError('permission-denied', 'Device not registered for this user');
    }

    const deviceData = deviceSnapshot.val();

    return {
      success: true,
      userId: userId,
      deviceId: deviceId,
      deviceName: deviceData.name,
      isValid: true
    };

  } catch (error) {
    console.error('Error validating unified access:', error);
    throw new functions.https.HttpsError('internal', 'Failed to validate access');
  }
});

// Cloud Function to get user device list (for admin purposes)
exports.getUserDevices = functions.https.onCall(async (data, context) => {
  try {
    const userId = context.auth?.uid;

    if (!userId) {
      throw new functions.https.HttpsError('unauthenticated', 'User must be authenticated');
    }

    // Get user's devices
    const devicesRef = admin.database().ref(`users/${userId}/devices`);
    const devicesSnapshot = await devicesRef.once('value');

    const devices = [];
    devicesSnapshot.forEach((childSnapshot) => {
      devices.push({
        id: childSnapshot.key,
        ...childSnapshot.val()
      });
    });

    return {
      success: true,
      userId: userId,
      devices: devices,
      deviceCount: devices.length
    };

  } catch (error) {
    console.error('Error getting user devices:', error);
    throw new functions.https.HttpsError('internal', 'Failed to get user devices');
  }
});

// Scheduled function to clean up expired pairing tokens
exports.cleanupExpiredTokens = functions.pubsub
  .schedule('every 6 hours')
  .onRun(async (context) => {
    try {
      const currentTime = Date.now();
      const tokensRef = admin.database().ref('pairing_tokens');
      const tokensSnapshot = await tokensRef.once('value');

      let cleanedCount = 0;
      const promises = [];

      tokensSnapshot.forEach((childSnapshot) => {
        const tokenData = childSnapshot.val();
        if (currentTime > tokenData.expiresAt) {
          promises.push(childSnapshot.ref.remove());
          cleanedCount++;
        }
      });

      await Promise.all(promises);

      console.log(`Cleaned up ${cleanedCount} expired pairing tokens`);
      return null;

    } catch (error) {
      console.error('Error cleaning up expired tokens:', error);
      return null;
    }
  });</content>
<parameter name="filePath">firebase-functions/index.js