/**
 * WebSocket server for real-time push to connected clients (Mac, Web, Android).
 *
 * Architecture:
 *   - Clients authenticate via a JWT token passed as a query parameter on connect.
 *   - Each client subscribes to one or more channels (messages, contacts, calls, etc.).
 *   - The server maintains an in-memory map of userId -> Set<WebSocket> for routing.
 *   - Route handlers call broadcastToUser() to push updates to connected clients.
 *   - WebRTC signaling is also relayed here for SyncFlow calls between devices.
 *   - A 30-second heartbeat (ping/pong) detects and cleans up dead connections.
 */

import { WebSocketServer, WebSocket } from 'ws';
import { IncomingMessage, Server as HttpServer } from 'http';
import { verifyToken, TokenPayload } from './auth';
import { isDeviceBlacklisted } from './redis';
import { pool } from './database';
import { log } from './logger';

interface AuthenticatedWebSocket extends WebSocket {
  userId?: string;
  deviceId?: string;
  subscriptions: Set<string>;
  isAlive: boolean;
}

interface SubscriptionMessage {
  type: 'subscribe' | 'unsubscribe' | 'webrtc_signal';
  channel?: string; // e.g., 'messages', 'contacts', 'calls', or 'all'
  channels?: string[]; // bulk subscribe: ['messages', 'contacts', 'calls']
  // WebRTC signal relay fields
  callId?: string;
  signalType?: string;
  signalData?: any;
  toDevice?: string;
}

interface BroadcastMessage {
  type: string;
  data: any;
}

// Store active connections by userId
const userConnections = new Map<string, Set<AuthenticatedWebSocket>>();

// In-memory cache for WebRTC call participant lookups (avoids DB query on every signal)
interface CallParticipants {
  callerId: string;
  calleeId: string | null;
  cachedAt: number;
}
const CALL_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
const callParticipantCache = new Map<string, CallParticipants>();

function getCachedCallParticipants(callId: string): CallParticipants | null {
  const entry = callParticipantCache.get(callId);
  if (!entry) return null;
  if (Date.now() - entry.cachedAt > CALL_CACHE_TTL_MS) {
    callParticipantCache.delete(callId);
    return null;
  }
  return entry;
}

function setCachedCallParticipants(callId: string, callerId: string, calleeId: string | null): void {
  callParticipantCache.set(callId, { callerId, calleeId, cachedAt: Date.now() });
}

export function clearCallCache(callId: string): void {
  callParticipantCache.delete(callId);
}

export function createWebSocketServer(server: HttpServer): WebSocketServer {
  const wss = new WebSocketServer({ server, maxPayload: 65536 }); // 64KB max message size

  log.info('WebSocket server attached to HTTP server');

  // Heartbeat interval
  const heartbeatInterval = setInterval(() => {
    wss.clients.forEach((ws) => {
      const client = ws as AuthenticatedWebSocket;
      if (!client.isAlive) {
        client.terminate();
        return;
      }
      client.isAlive = false;
      client.ping();
    });
  }, 30000);

  wss.on('close', () => {
    clearInterval(heartbeatInterval);
  });

  wss.on('connection', async (ws: WebSocket, req: IncomingMessage) => {
    const client = ws as AuthenticatedWebSocket;
    client.subscriptions = new Set();
    client.isAlive = true;

    // Authenticate from URL query parameter
    const url = new URL(req.url || '', `http://${req.headers.host}`);
    const token = url.searchParams.get('token');

    if (!token) {
      client.close(4001, 'Missing authentication token');
      return;
    }

    const payload = verifyToken(token);
    if (!payload || payload.type !== 'access') {
      client.close(4002, 'Invalid authentication token');
      return;
    }

    // Check device blacklist (removed devices should not maintain WS connections)
    if (payload.deviceId && await isDeviceBlacklisted(payload.deviceId)) {
      client.close(4003, 'Device has been removed');
      return;
    }

    client.userId = payload.pairedUid || payload.sub;
    client.deviceId = payload.deviceId;

    // Add to user connections
    if (!userConnections.has(client.userId)) {
      userConnections.set(client.userId, new Set());
    }
    userConnections.get(client.userId)!.add(client);

    // Send connection confirmation
    client.send(JSON.stringify({
      type: 'connected',
      userId: client.userId,
      deviceId: client.deviceId,
    }));

    // Handle pong
    client.on('pong', () => {
      client.isAlive = true;
    });

    // Handle messages
    client.on('message', (data) => {
      try {
        const message = JSON.parse(data.toString()) as SubscriptionMessage;
        if (message.type === 'webrtc_signal') {
          handleWebRTCSignal(client, message);
        } else {
          handleClientMessage(client, message);
        }
      } catch (error) {
        log.error('WebSocket message parse error', { error: (error as Error).message, userId: client.userId });
      }
    });

    // Handle close
    client.on('close', () => {
      if (client.userId) {
        const connections = userConnections.get(client.userId);
        if (connections) {
          connections.delete(client);
          if (connections.size === 0) {
            userConnections.delete(client.userId);
          }
        }
      }
    });

    // Handle errors
    client.on('error', (error) => {
      log.error('WebSocket connection error', { userId: client.userId, error: error.message });
    });
  });

  return wss;
}

const VALID_CHANNELS = [
  'messages', 'contacts', 'calls', 'devices', 'outgoing', 'call_requests',
  'clipboard', 'dnd', 'media', 'hotspot', 'phone_status', 'typing',
  'notifications', 'voicemails', 'e2ee', 'photos', 'file_transfers',
  'continuity', 'scheduled_messages', 'find_phone', 'subscription', 'spam',
];

function handleClientMessage(client: AuthenticatedWebSocket, message: SubscriptionMessage): void {
  // Determine which channels to operate on
  let channelsToProcess: string[];

  if (message.channels && Array.isArray(message.channels)) {
    // Bulk subscribe/unsubscribe: { type: "subscribe", channels: ["messages", "contacts"] }
    channelsToProcess = message.channels;
  } else if (message.channel === 'all') {
    // Subscribe-all shortcut: { type: "subscribe", channel: "all" }
    channelsToProcess = [...VALID_CHANNELS];
  } else if (message.channel) {
    // Single channel (original format): { type: "subscribe", channel: "messages" }
    channelsToProcess = [message.channel];
  } else {
    client.send(JSON.stringify({ type: 'error', message: 'Missing channel or channels field' }));
    return;
  }

  // Validate all channels first
  const invalidChannels = channelsToProcess.filter((ch) => !VALID_CHANNELS.includes(ch));
  if (invalidChannels.length > 0) {
    client.send(JSON.stringify({
      type: 'error',
      message: `Invalid channel(s): ${invalidChannels.join(', ')}`,
    }));
    return;
  }

  if (message.type === 'subscribe') {
    const subscribed: string[] = [];
    for (const ch of channelsToProcess) {
      client.subscriptions.add(ch);
      subscribed.push(ch);
    }
    // For single channel, send the original response format for backward compatibility
    if (!message.channels && message.channel !== 'all' && subscribed.length === 1) {
      client.send(JSON.stringify({ type: 'subscribed', channel: subscribed[0] }));
    } else {
      client.send(JSON.stringify({ type: 'subscribed', channels: subscribed }));
    }
  } else if (message.type === 'unsubscribe') {
    const unsubscribed: string[] = [];
    for (const ch of channelsToProcess) {
      client.subscriptions.delete(ch);
      unsubscribed.push(ch);
    }
    // For single channel, send the original response format for backward compatibility
    if (!message.channels && message.channel !== 'all' && unsubscribed.length === 1) {
      client.send(JSON.stringify({ type: 'unsubscribed', channel: unsubscribed[0] }));
    } else {
      client.send(JSON.stringify({ type: 'unsubscribed', channels: unsubscribed }));
    }
  }
}

async function handleWebRTCSignal(client: AuthenticatedWebSocket, message: SubscriptionMessage): Promise<void> {
  if (!client.userId || !message.callId || !message.signalType || !message.signalData) {
    client.send(JSON.stringify({ type: 'error', message: 'Invalid webrtc_signal: missing fields' }));
    return;
  }

  const signalMessage = {
    type: 'webrtc_signal',
    data: {
      callId: message.callId,
      signalType: message.signalType,
      signalData: message.signalData,
      fromDevice: client.deviceId || '',
    },
  };

  // Look up call participants (cached to avoid DB hit on every signal)
  try {
    let participants = getCachedCallParticipants(message.callId);
    if (!participants) {
      const rows = await pool.query(
        `SELECT user_id, callee_user_id FROM user_syncflow_calls WHERE id = $1`,
        [message.callId]
      );
      if (rows.rows.length > 0) {
        const { user_id: callerId, callee_user_id: calleeId } = rows.rows[0];
        setCachedCallParticipants(message.callId, callerId, calleeId);
        participants = getCachedCallParticipants(message.callId);
      }
    }

    if (participants) {
      const otherUserId = client.userId === participants.callerId ? participants.calleeId : participants.callerId;
      if (otherUserId && otherUserId !== client.userId) {
        broadcastToUser(otherUserId, 'calls', signalMessage);
      }
    }

    // Clear cache on call-ending signals (bye, hangup)
    if (message.signalType === 'bye' || message.signalType === 'hangup') {
      clearCallCache(message.callId);
    }
  } catch (err) {
    log.error('Error looking up call for WebRTC signaling', { callId: message.callId, error: (err as Error).message });
  }

  // Also broadcast to same-user's other devices
  broadcastToAllDevicesExcept(client.userId, client.deviceId || '', 'calls', signalMessage);
}

// Broadcast message to all connections for a user
export function broadcastToUser(
  userId: string,
  channel: string,
  message: BroadcastMessage
): void {
  const connections = userConnections.get(userId);
  if (!connections) return;

  const messageStr = JSON.stringify(message);

  connections.forEach((client) => {
    if (client.readyState === WebSocket.OPEN && client.subscriptions.has(channel)) {
      client.send(messageStr);
    }
  });
}

// Broadcast to specific device
export function broadcastToDevice(
  userId: string,
  deviceId: string,
  message: BroadcastMessage
): void {
  const connections = userConnections.get(userId);
  if (!connections) return;

  const messageStr = JSON.stringify(message);

  connections.forEach((client) => {
    if (client.readyState === WebSocket.OPEN && client.deviceId === deviceId) {
      client.send(messageStr);
    }
  });
}

// Get connected device count for a user
export function getConnectedDeviceCount(userId: string): number {
  return userConnections.get(userId)?.size || 0;
}

// Check if user has any connected devices
export function isUserOnline(userId: string): boolean {
  return userConnections.has(userId) && userConnections.get(userId)!.size > 0;
}

// Broadcast to all of a user's devices except the sender device
export function broadcastToAllDevicesExcept(
  userId: string,
  excludeDeviceId: string,
  channel: string,
  message: BroadcastMessage
): void {
  const connections = userConnections.get(userId);
  if (!connections) return;

  const messageStr = JSON.stringify(message);

  connections.forEach((client) => {
    if (
      client.readyState === WebSocket.OPEN &&
      client.deviceId !== excludeDeviceId &&
      client.subscriptions.has(channel)
    ) {
      client.send(messageStr);
    }
  });
}

// Get total WebSocket connection count across all users
export function getConnectionCount(): number {
  let total = 0;
  userConnections.forEach((connections) => {
    total += connections.size;
  });
  return total;
}

// Get all online users with their device info (for admin use)
export function getOnlineUsers(): { userId: string; deviceCount: number; devices: string[] }[] {
  const result: { userId: string; deviceCount: number; devices: string[] }[] = [];

  userConnections.forEach((connections, userId) => {
    const devices: string[] = [];
    connections.forEach((client) => {
      if (client.readyState === WebSocket.OPEN && client.deviceId) {
        devices.push(client.deviceId);
      }
    });
    if (devices.length > 0) {
      result.push({
        userId,
        deviceCount: devices.length,
        devices,
      });
    }
  });

  return result;
}
