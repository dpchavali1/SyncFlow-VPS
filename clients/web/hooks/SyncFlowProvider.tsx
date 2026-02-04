/**
 * SyncFlow React Context Provider
 *
 * Provides the SyncFlow service to the React component tree.
 */

import React, { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { SyncFlowService, SyncFlowState, getSyncFlowService } from '../services/SyncFlowService';

interface SyncFlowContextValue {
  service: SyncFlowService;
  state: SyncFlowState;
  isInitialized: boolean;
}

const SyncFlowContext = createContext<SyncFlowContextValue | null>(null);

interface SyncFlowProviderProps {
  children: ReactNode;
  apiUrl?: string;
}

export function SyncFlowProvider({ children, apiUrl }: SyncFlowProviderProps) {
  const [service] = useState(() => {
    if (apiUrl) {
      return new SyncFlowService(apiUrl);
    }
    return getSyncFlowService();
  });

  const [state, setState] = useState<SyncFlowState>(service.getState());
  const [isInitialized, setIsInitialized] = useState(false);

  useEffect(() => {
    // Subscribe to state changes
    const unsubscribe = service.on('stateChange', (newState: SyncFlowState) => {
      setState(newState);
    });

    // Initialize service
    service.init().then(() => {
      setState(service.getState());
      setIsInitialized(true);
    });

    return () => {
      unsubscribe();
    };
  }, [service]);

  return (
    <SyncFlowContext.Provider value={{ service, state, isInitialized }}>
      {children}
    </SyncFlowContext.Provider>
  );
}

export function useSyncFlowContext(): SyncFlowContextValue {
  const context = useContext(SyncFlowContext);
  if (!context) {
    throw new Error('useSyncFlowContext must be used within a SyncFlowProvider');
  }
  return context;
}

// Convenience hooks that use the context

export function useSyncFlowService(): SyncFlowService {
  const { service } = useSyncFlowContext();
  return service;
}

export function useSyncFlowState(): SyncFlowState {
  const { state } = useSyncFlowContext();
  return state;
}

export function useIsAuthenticated(): boolean {
  const { state } = useSyncFlowContext();
  return state.isAuthenticated;
}

export function useIsConnected(): boolean {
  const { state } = useSyncFlowContext();
  return state.isConnected;
}

export function useMessages() {
  const { state } = useSyncFlowContext();
  return state.messages;
}

export function useContacts() {
  const { state } = useSyncFlowContext();
  return state.contacts;
}

export function useCalls() {
  const { state } = useSyncFlowContext();
  return state.calls;
}

export function useDevices() {
  const { state } = useSyncFlowContext();
  return state.devices;
}

export default SyncFlowProvider;
