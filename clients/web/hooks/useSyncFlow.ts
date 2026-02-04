/**
 * React Hooks for SyncFlow Service
 *
 * Provides React-friendly hooks for using the SyncFlow VPS service.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { SyncFlowService, SyncFlowState, getSyncFlowService } from '../services/SyncFlowService';
import { Message, Contact, CallHistoryEntry, Device } from '../SyncFlowClient';

// ==================== Main Hook ====================

export function useSyncFlow() {
  const [state, setState] = useState<SyncFlowState>({
    isAuthenticated: false,
    isConnected: false,
    isPaired: false,
    userId: null,
    deviceId: null,
    messages: [],
    contacts: [],
    calls: [],
    devices: [],
  });
  const [isLoading, setIsLoading] = useState(true);
  const serviceRef = useRef<SyncFlowService | null>(null);

  useEffect(() => {
    const service = getSyncFlowService();
    serviceRef.current = service;

    // Subscribe to state changes
    const unsubscribe = service.on('stateChange', (newState: SyncFlowState) => {
      setState(newState);
    });

    // Initialize
    service.init().then(() => {
      setState(service.getState());
      setIsLoading(false);
    });

    return () => {
      unsubscribe();
    };
  }, []);

  const service = serviceRef.current;

  return {
    ...state,
    isLoading,
    service,
  };
}

// ==================== Authentication Hook ====================

export function useSyncFlowAuth() {
  const { isAuthenticated, isPaired, userId, deviceId, service, isLoading } = useSyncFlow();
  const [error, setError] = useState<string | null>(null);

  const authenticateAnonymous = useCallback(async () => {
    if (!service) return null;
    try {
      setError(null);
      return await service.authenticateAnonymous();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Authentication failed');
      return null;
    }
  }, [service]);

  const initiatePairing = useCallback(async (deviceName: string) => {
    if (!service) return null;
    try {
      setError(null);
      return await service.initiatePairing(deviceName);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Pairing failed');
      return null;
    }
  }, [service]);

  const pollPairingStatus = useCallback(async (token: string) => {
    if (!service) return false;
    try {
      return await service.pollPairingStatus(token);
    } catch (e) {
      return false;
    }
  }, [service]);

  const logout = useCallback(async () => {
    if (!service) return;
    try {
      setError(null);
      await service.logout();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Logout failed');
    }
  }, [service]);

  return {
    isAuthenticated,
    isPaired,
    userId,
    deviceId,
    isLoading,
    error,
    authenticateAnonymous,
    initiatePairing,
    pollPairingStatus,
    logout,
  };
}

// ==================== Messages Hook ====================

export function useSyncFlowMessages() {
  const { messages, isConnected, service, isLoading: initialLoading } = useSyncFlow();
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadMessages = useCallback(async (options?: { limit?: number; before?: number }) => {
    if (!service) return [];
    try {
      setIsLoading(true);
      setError(null);
      const result = await service.loadMessages(options);
      return result;
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load messages');
      return [];
    } finally {
      setIsLoading(false);
    }
  }, [service]);

  const sendMessage = useCallback(async (address: string, body: string) => {
    if (!service) return false;
    try {
      setError(null);
      await service.sendMessage(address, body);
      return true;
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to send message');
      return false;
    }
  }, [service]);

  const markAsRead = useCallback(async (messageId: string) => {
    if (!service) return;
    try {
      await service.markAsRead(messageId);
    } catch (e) {
      console.error('Failed to mark as read:', e);
    }
  }, [service]);

  return {
    messages,
    isLoading: isLoading || initialLoading,
    isConnected,
    error,
    loadMessages,
    sendMessage,
    markAsRead,
  };
}

// ==================== Contacts Hook ====================

export function useSyncFlowContacts() {
  const { contacts, service, isLoading: initialLoading } = useSyncFlow();
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchResults, setSearchResults] = useState<Contact[]>([]);

  const loadContacts = useCallback(async () => {
    if (!service) return [];
    try {
      setIsLoading(true);
      setError(null);
      const result = await service.loadContacts();
      return result;
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load contacts');
      return [];
    } finally {
      setIsLoading(false);
    }
  }, [service]);

  const searchContacts = useCallback(async (query: string) => {
    if (!service) return [];
    try {
      const results = await service.searchContacts(query);
      setSearchResults(results);
      return results;
    } catch (e) {
      console.error('Search failed:', e);
      return [];
    }
  }, [service]);

  return {
    contacts,
    searchResults,
    isLoading: isLoading || initialLoading,
    error,
    loadContacts,
    searchContacts,
  };
}

// ==================== Call History Hook ====================

export function useSyncFlowCalls() {
  const { calls, service, isLoading: initialLoading } = useSyncFlow();
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadCallHistory = useCallback(async (options?: { limit?: number; before?: number }) => {
    if (!service) return [];
    try {
      setIsLoading(true);
      setError(null);
      const result = await service.loadCallHistory(options);
      return result;
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load call history');
      return [];
    } finally {
      setIsLoading(false);
    }
  }, [service]);

  const makeCall = useCallback(async (phoneNumber: string) => {
    if (!service) return false;
    try {
      setError(null);
      await service.makeCall(phoneNumber);
      return true;
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to request call');
      return false;
    }
  }, [service]);

  return {
    calls,
    isLoading: isLoading || initialLoading,
    error,
    loadCallHistory,
    makeCall,
  };
}

// ==================== Devices Hook ====================

export function useSyncFlowDevices() {
  const { devices, service, isLoading: initialLoading } = useSyncFlow();
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadDevices = useCallback(async () => {
    if (!service) return [];
    try {
      setIsLoading(true);
      setError(null);
      const result = await service.loadDevices();
      return result;
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load devices');
      return [];
    } finally {
      setIsLoading(false);
    }
  }, [service]);

  const removeDevice = useCallback(async (deviceId: string) => {
    if (!service) return false;
    try {
      setError(null);
      await service.removeDevice(deviceId);
      return true;
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to remove device');
      return false;
    }
  }, [service]);

  return {
    devices,
    isLoading: isLoading || initialLoading,
    error,
    loadDevices,
    removeDevice,
  };
}

// ==================== Connection Status Hook ====================

export function useSyncFlowConnection() {
  const { isConnected, service } = useSyncFlow();
  const [lastConnected, setLastConnected] = useState<Date | null>(null);

  useEffect(() => {
    if (!service) return;

    const onConnected = () => setLastConnected(new Date());
    const unsubscribe = service.on('connected', onConnected);

    return () => {
      unsubscribe();
    };
  }, [service]);

  return {
    isConnected,
    lastConnected,
  };
}

// ==================== New Message Listener Hook ====================

export function useNewMessage(callback: (message: Message) => void) {
  const { service } = useSyncFlow();

  useEffect(() => {
    if (!service) return;

    const unsubscribe = service.on('message', callback);

    return () => {
      unsubscribe();
    };
  }, [service, callback]);
}
