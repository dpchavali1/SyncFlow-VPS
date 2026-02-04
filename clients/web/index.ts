/**
 * SyncFlow VPS Web Client
 *
 * Complete client library for integrating with SyncFlow VPS API.
 */

// Low-level API client
export { default as SyncFlowClient } from './SyncFlowClient';
export type {
  Message,
  Contact,
  CallHistoryEntry,
  Device,
  User,
  TokenPair,
  SyncResponse,
  OutgoingMessage,
  CallRequest,
} from './SyncFlowClient';

// High-level service
export { SyncFlowService, getSyncFlowService } from './services/SyncFlowService';
export type { SyncFlowState } from './services/SyncFlowService';

// React hooks
export {
  useSyncFlow,
  useSyncFlowAuth,
  useSyncFlowMessages,
  useSyncFlowContacts,
  useSyncFlowCalls,
  useSyncFlowDevices,
  useSyncFlowConnection,
  useNewMessage,
} from './hooks/useSyncFlow';

// React context
export {
  SyncFlowProvider,
  useSyncFlowContext,
  useSyncFlowService,
  useSyncFlowState,
  useIsAuthenticated,
  useIsConnected,
  useMessages,
  useContacts,
  useCalls,
  useDevices,
} from './hooks/SyncFlowProvider';
