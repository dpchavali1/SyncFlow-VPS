import { WebSocketServer, WebSocket } from 'ws';
import { IncomingMessage } from 'http';
import { verifyToken, TokenPayload } from './auth';
import { pool } from './database';
import { PoolClient } from 'pg';

interface AuthenticatedWebSocket extends WebSocket {
  userId?: string;
  deviceId?: string;
  subscriptions: Set<string>;
  isAlive: boolean;
}

interface SubscriptionMessage {
  type: 'subscribe' | 'unsubscribe';
  channel: string; // e.g., 'messages', 'contacts', 'calls'
}

interface BroadcastMessage {
  type: string;
  data: any;
}

// Store active connections by userId
const userConnections = new Map<string, Set<AuthenticatedWebSocket>>();

// PostgreSQL LISTEN clients
const listenClients: PoolClient[] = [];

export function createWebSocketServer(port: number): WebSocketServer {
  const wss = new WebSocketServer({ port });

  console.log(`WebSocket server started on port ${port}`);

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
    // Clean up PostgreSQL listeners
    listenClients.forEach((client) => client.release());
  });

  wss.on('connection', (ws: WebSocket, req: IncomingMessage) => {
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

    client.userId = payload.pairedUid || payload.sub;
    client.deviceId = payload.deviceId;

    // Add to user connections
    if (!userConnections.has(client.userId)) {
      userConnections.set(client.userId, new Set());
    }
    userConnections.get(client.userId)!.add(client);

    console.log(`WebSocket connected: user=${client.userId}, device=${client.deviceId}`);

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
        handleClientMessage(client, message);
      } catch (error) {
        console.error('WebSocket message parse error:', error);
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
      console.log(`WebSocket disconnected: user=${client.userId}, device=${client.deviceId}`);
    });

    // Handle errors
    client.on('error', (error) => {
      console.error(`WebSocket error for user=${client.userId}:`, error.message);
    });
  });

  // Set up PostgreSQL listeners for real-time updates
  setupDatabaseListeners();

  return wss;
}

function handleClientMessage(client: AuthenticatedWebSocket, message: SubscriptionMessage): void {
  const validChannels = ['messages', 'contacts', 'calls', 'devices', 'outgoing', 'call_requests'];

  if (!validChannels.includes(message.channel)) {
    client.send(JSON.stringify({ type: 'error', message: 'Invalid channel' }));
    return;
  }

  if (message.type === 'subscribe') {
    client.subscriptions.add(message.channel);
    client.send(JSON.stringify({ type: 'subscribed', channel: message.channel }));
  } else if (message.type === 'unsubscribe') {
    client.subscriptions.delete(message.channel);
    client.send(JSON.stringify({ type: 'unsubscribed', channel: message.channel }));
  }
}

async function setupDatabaseListeners(): Promise<void> {
  // Listen to notifications from PostgreSQL triggers
  const tables = [
    'user_messages',
    'user_contacts',
    'user_call_history',
    'user_devices',
    'user_outgoing_messages',
  ];

  try {
    const client = await pool.connect();
    listenClients.push(client);

    client.on('notification', (msg) => {
      if (msg.payload) {
        handleDatabaseNotification(msg.channel, msg.payload);
      }
    });

    // We'll listen for specific user channels dynamically
    // For now, set up a general pattern
    client.on('error', (err) => {
      console.error('PostgreSQL listener error:', err);
    });

    console.log('PostgreSQL listeners set up');
  } catch (error) {
    console.error('Failed to set up database listeners:', error);
  }
}

function handleDatabaseNotification(channel: string, payload: string): void {
  try {
    const data = JSON.parse(payload);
    const userId = channel.split('_').pop(); // Extract userId from channel name

    if (!userId) return;

    // Determine message type based on channel and action
    let messageType: string;
    let broadcastChannel: string;

    if (channel.startsWith('user_messages_')) {
      broadcastChannel = 'messages';
      messageType = `message_${data.action.toLowerCase()}`;
    } else if (channel.startsWith('user_contacts_')) {
      broadcastChannel = 'contacts';
      messageType = `contact_${data.action.toLowerCase()}`;
    } else if (channel.startsWith('user_call_history_')) {
      broadcastChannel = 'calls';
      messageType = 'call_added';
    } else if (channel.startsWith('user_devices_')) {
      broadcastChannel = 'devices';
      messageType = data.action === 'DELETE' ? 'device_removed' : 'device_added';
    } else if (channel.startsWith('user_outgoing_messages_')) {
      broadcastChannel = 'outgoing';
      messageType = 'outgoing_message';
    } else {
      return;
    }

    // Broadcast to all connected clients for this user
    broadcastToUser(userId, broadcastChannel, {
      type: messageType,
      data: data,
    });
  } catch (error) {
    console.error('Error handling database notification:', error);
  }
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
