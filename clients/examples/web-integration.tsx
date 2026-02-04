/**
 * Example: Integrating SyncFlow VPS into a React Web App
 *
 * This shows how to replace Firebase with VPS backend.
 */

import React, { useEffect, useState, useCallback } from 'react';
import {
  SyncFlowProvider,
  useSyncFlowAuth,
  useSyncFlowMessages,
  useSyncFlowContacts,
  useNewMessage,
  useIsConnected,
} from '../web';

// ==================== App Entry Point ====================

export function App() {
  return (
    <SyncFlowProvider apiUrl="http://5.78.188.206">
      <MainApp />
    </SyncFlowProvider>
  );
}

// ==================== Main App with Routing ====================

function MainApp() {
  const { isAuthenticated, isLoading } = useSyncFlowAuth();

  if (isLoading) {
    return <LoadingScreen />;
  }

  if (!isAuthenticated) {
    return <PairingScreen />;
  }

  return <MessagingApp />;
}

// ==================== Pairing Screen ====================

function PairingScreen() {
  const { initiatePairing, pollPairingStatus, error } = useSyncFlowAuth();
  const [pairingToken, setPairingToken] = useState<string | null>(null);
  const [deviceName, setDeviceName] = useState('My Web Browser');

  const handleStartPairing = async () => {
    const token = await initiatePairing(deviceName);
    if (token) {
      setPairingToken(token);
      // Start polling for approval
      pollForApproval(token);
    }
  };

  const pollForApproval = async (token: string) => {
    const maxAttempts = 60; // 2 minutes
    for (let i = 0; i < maxAttempts; i++) {
      await new Promise((r) => setTimeout(r, 2000));
      const approved = await pollPairingStatus(token);
      if (approved) {
        return; // Auth state will update automatically
      }
    }
    setPairingToken(null); // Timeout
  };

  return (
    <div className="flex flex-col items-center justify-center min-h-screen p-8">
      <h1 className="text-3xl font-bold mb-4">SyncFlow</h1>
      <p className="text-gray-600 mb-8">Connect your phone to continue</p>

      {pairingToken ? (
        <div className="text-center">
          <p className="mb-4">Enter this code on your Android device:</p>
          <div className="text-4xl font-mono font-bold bg-gray-100 p-4 rounded">
            {pairingToken}
          </div>
          <p className="mt-4 text-sm text-gray-500">Waiting for approval...</p>
        </div>
      ) : (
        <div className="flex flex-col gap-4 w-64">
          <input
            type="text"
            value={deviceName}
            onChange={(e) => setDeviceName(e.target.value)}
            placeholder="Device name"
            className="px-4 py-2 border rounded"
          />
          <button
            onClick={handleStartPairing}
            className="px-6 py-3 bg-blue-500 text-white rounded hover:bg-blue-600"
          >
            Start Pairing
          </button>
        </div>
      )}

      {error && <p className="mt-4 text-red-500">{error}</p>}
    </div>
  );
}

// ==================== Messaging App ====================

function MessagingApp() {
  const { messages, loadMessages, sendMessage, isLoading } = useSyncFlowMessages();
  const { contacts, loadContacts } = useSyncFlowContacts();
  const isConnected = useIsConnected();
  const [selectedAddress, setSelectedAddress] = useState<string | null>(null);
  const [messageText, setMessageText] = useState('');

  // Load data on mount
  useEffect(() => {
    loadMessages();
    loadContacts();
  }, []);

  // Listen for new messages
  useNewMessage((message) => {
    // Show notification for new messages
    if (Notification.permission === 'granted') {
      new Notification(`New message from ${message.contactName || message.address}`, {
        body: message.body,
      });
    }
  });

  // Group messages by address (conversation threads)
  const conversations = messages.reduce((acc, msg) => {
    const key = msg.address;
    if (!acc[key]) {
      acc[key] = {
        address: key,
        contactName: msg.contactName,
        messages: [],
        lastMessage: msg,
      };
    }
    acc[key].messages.push(msg);
    if (msg.date > acc[key].lastMessage.date) {
      acc[key].lastMessage = msg;
    }
    return acc;
  }, {} as Record<string, { address: string; contactName: string | null; messages: typeof messages; lastMessage: typeof messages[0] }>);

  const sortedConversations = Object.values(conversations).sort(
    (a, b) => b.lastMessage.date - a.lastMessage.date
  );

  const handleSend = async () => {
    if (!selectedAddress || !messageText.trim()) return;
    await sendMessage(selectedAddress, messageText);
    setMessageText('');
  };

  return (
    <div className="flex h-screen">
      {/* Sidebar - Conversations */}
      <div className="w-80 border-r flex flex-col">
        {/* Header */}
        <div className="p-4 border-b flex items-center justify-between">
          <h1 className="font-bold">Messages</h1>
          <div className="flex items-center gap-2">
            <div
              className={`w-2 h-2 rounded-full ${isConnected ? 'bg-green-500' : 'bg-red-500'}`}
            />
            <span className="text-xs text-gray-500">
              {isConnected ? 'Connected' : 'Disconnected'}
            </span>
          </div>
        </div>

        {/* Conversation list */}
        <div className="flex-1 overflow-y-auto">
          {sortedConversations.map((conv) => (
            <div
              key={conv.address}
              onClick={() => setSelectedAddress(conv.address)}
              className={`p-4 border-b cursor-pointer hover:bg-gray-50 ${
                selectedAddress === conv.address ? 'bg-blue-50' : ''
              }`}
            >
              <div className="flex justify-between">
                <span className="font-medium">
                  {conv.contactName || conv.address}
                </span>
                <span className="text-xs text-gray-500">
                  {formatTime(conv.lastMessage.date)}
                </span>
              </div>
              <p className="text-sm text-gray-600 truncate">
                {conv.lastMessage.body}
              </p>
            </div>
          ))}
        </div>
      </div>

      {/* Main - Messages */}
      <div className="flex-1 flex flex-col">
        {selectedAddress ? (
          <>
            {/* Message header */}
            <div className="p-4 border-b">
              <h2 className="font-medium">
                {conversations[selectedAddress]?.contactName || selectedAddress}
              </h2>
            </div>

            {/* Messages */}
            <div className="flex-1 overflow-y-auto p-4 flex flex-col-reverse gap-2">
              {conversations[selectedAddress]?.messages
                .sort((a, b) => b.date - a.date)
                .map((msg) => (
                  <div
                    key={msg.id}
                    className={`p-3 rounded-lg max-w-xs ${
                      msg.type === 2
                        ? 'bg-blue-500 text-white self-end'
                        : 'bg-gray-200 self-start'
                    }`}
                  >
                    <p>{msg.body}</p>
                    <p className="text-xs opacity-70 mt-1">
                      {formatTime(msg.date)}
                    </p>
                  </div>
                ))}
            </div>

            {/* Input */}
            <div className="p-4 border-t flex gap-2">
              <input
                type="text"
                value={messageText}
                onChange={(e) => setMessageText(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSend()}
                placeholder="Type a message..."
                className="flex-1 px-4 py-2 border rounded"
              />
              <button
                onClick={handleSend}
                disabled={!messageText.trim()}
                className="px-6 py-2 bg-blue-500 text-white rounded disabled:opacity-50"
              >
                Send
              </button>
            </div>
          </>
        ) : (
          <div className="flex-1 flex items-center justify-center text-gray-500">
            Select a conversation to start messaging
          </div>
        )}
      </div>
    </div>
  );
}

// ==================== Helpers ====================

function LoadingScreen() {
  return (
    <div className="flex items-center justify-center min-h-screen">
      <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-500" />
    </div>
  );
}

function formatTime(timestamp: number): string {
  const date = new Date(timestamp);
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const days = Math.floor(diff / (1000 * 60 * 60 * 24));

  if (days === 0) {
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  } else if (days === 1) {
    return 'Yesterday';
  } else if (days < 7) {
    return date.toLocaleDateString([], { weekday: 'short' });
  } else {
    return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
  }
}
