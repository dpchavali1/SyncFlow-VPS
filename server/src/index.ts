import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import path from 'path';
import http from 'http';
import { config } from './config';
import { pool, checkDatabaseHealth } from './services/database';
import { redis, checkRedisHealth } from './services/redis';
import { createWebSocketServer, getConnectionCount } from './services/websocket';
import { initLogger, log } from './services/logger';
import { initializeFCM, retryStaleOutgoingMessages } from './services/push';
import { maintenanceMiddleware } from './middleware/maintenance';
import { startDailyCleanup, stopDailyCleanup } from './services/dailyCleanup';
import { storage, createFileRouter } from './services/storage';
import { validateLicense, getLicenseStatus, startLicenseHeartbeat, stopLicenseHeartbeat } from './services/license';
import fs from 'fs/promises';

// Initialize log capture before anything else
initLogger();

// Import routes
import authRoutes from './routes/auth';
import messagesRoutes from './routes/messages';
import contactsRoutes from './routes/contacts';
import callsRoutes from './routes/calls';
import devicesRoutes from './routes/devices';
import adminRoutes from './routes/admin';
import clipboardRoutes from './routes/clipboard';
import dndRoutes from './routes/dnd';
import mediaRoutes from './routes/media';
import hotspotRoutes from './routes/hotspot';
import findPhoneRoutes from './routes/findPhone';
import linksRoutes from './routes/links';
import phoneStatusRoutes from './routes/phoneStatus';
import fileTransfersRoutes from './routes/fileTransfers';
import readReceiptsRoutes from './routes/readReceipts';
import typingRoutes from './routes/typing';
import scheduledMessagesRoutes from './routes/scheduledMessages';
import notificationsRoutes from './routes/notifications';
import voicemailsRoutes from './routes/voicemails';
import continuityRoutes from './routes/continuity';
import e2eeRoutes from './routes/e2ee';
import spamRoutes from './routes/spam';

import usageRoutes from './routes/usage';
import accountRoutes from './routes/account';
import supportRoutes from './routes/support';
import analyticsRoutes from './routes/analytics';

// API Response Convention (for new endpoints going forward):
//   Success: { success: true, ...data }
//   Error:   { error: 'message' }
// Legacy endpoints may return bare data objects; do not change those to avoid
// breaking existing clients. All new or refactored endpoints should use this format.

const app = express();

// Security middleware
app.use(helmet({
  contentSecurityPolicy: false, // Disable for API
}));

// CORS
app.use(cors({
  origin: config.corsOrigins,
  credentials: true,
}));

// Version header on all responses
app.use((_req, res, next) => {
  res.setHeader('X-SyncFlow-Version', '1.0.0');
  next();
});

// Body parsing (skip JSON parsing for Stripe webhook which needs raw body)
app.use((req, res, next) => {
  if (req.path === '/api/usage/subscription/webhook') {
    next();
  } else {
    express.json({ limit: '10mb' })(req, res, next);
  }
});
app.use(express.urlencoded({ extended: true }));

// Request logging (structured)
app.use((req, res, next) => {
  const start = Date.now();
  res.on('finish', () => {
    const duration = Date.now() - start;
    if (config.nodeEnv === 'development' || duration > 1000) {
      log.info('HTTP request', { method: req.method, path: req.path, status: res.statusCode, durationMs: duration });
    }
  });
  next();
});

// Health check endpoint
app.get('/health', async (req, res) => {
  const dbHealthy = await checkDatabaseHealth();
  const redisHealthy = await checkRedisHealth();

  const status = dbHealthy && redisHealthy ? 200 : 503;

  res.status(status).json({
    status: status === 200 ? 'healthy' : 'unhealthy',
    timestamp: new Date().toISOString(),
    services: {
      database: dbHealthy ? 'up' : 'down',
      redis: redisHealthy ? 'up' : 'down',
    },
    websocket: {
      connections: getConnectionCount(),
    },
    selfHosted: config.selfHosted,
    storageBackend: storage.isConfigured() ? 'ready' : 'not configured',
    license: (() => {
      const ls = getLicenseStatus();
      return {
        plan: ls.plan,
        valid: ls.valid,
        expires: ls.expires,
        maxUsers: ls.maxUsers,
        maxDevices: ls.maxDevices,
      };
    })(),
  });
});

// Analytics tracking (before maintenance so it works during maintenance)
app.use('/api/analytics', analyticsRoutes);

// Maintenance mode check (after health check, before API routes)
app.use(maintenanceMiddleware);

// API routes
app.use('/api/auth', authRoutes);
app.use('/api/messages', messagesRoutes);
app.use('/api/contacts', contactsRoutes);
app.use('/api/calls', callsRoutes);
app.use('/api/devices', devicesRoutes);
app.use('/api/admin', adminRoutes);
app.use('/api/clipboard', clipboardRoutes);
app.use('/api/dnd', dndRoutes);
app.use('/api/media', mediaRoutes);
app.use('/api/hotspot', hotspotRoutes);
app.use('/api/find-phone', findPhoneRoutes);
app.use('/api/links', linksRoutes);
app.use('/api/phone-status', phoneStatusRoutes);
app.use('/api/file-transfers', fileTransfersRoutes);
app.use('/api/read-receipts', readReceiptsRoutes);
app.use('/api/typing', typingRoutes);
app.use('/api/scheduled-messages', scheduledMessagesRoutes);
app.use('/api/notifications', notificationsRoutes);
app.use('/api/voicemails', voicemailsRoutes);
app.use('/api/continuity', continuityRoutes);
app.use('/api/e2ee', e2eeRoutes);
app.use('/api/spam', spamRoutes);

// Local file storage endpoints (active only when using local storage backend)
app.use('/api/files', createFileRouter());

app.use('/api/usage', usageRoutes);
app.use('/api/account', accountRoutes);
app.use('/api/support', supportRoutes);
app.use('/api/support-chat', supportRoutes);

// Static file serving for app downloads (APK, DMG)
app.use('/downloads', express.static(path.join(__dirname, '..', 'downloads'), {
  maxAge: '7d',
  immutable: true,
}));

// 404 handler
app.use((req, res) => {
  res.status(404).json({ error: 'Not found' });
});

// Error handler
app.use((err: Error, req: express.Request, res: express.Response, next: express.NextFunction) => {
  log.error('Unhandled error', { error: err.message, stack: err.stack, path: req.path, method: req.method });
  res.status(500).json({ error: 'Internal server error' });
});

// Start servers
async function start() {
  try {
    // Self-hosted mode banner
    if (config.selfHosted) {
      log.info('=== SyncFlow Self-Hosted Mode ===');
      log.info(`Storage backend: ${storage.isConfigured() ? 'ready' : 'not configured'}`);

      // Validate license and start heartbeat
      const license = validateLicense();
      const licenseStatus = getLicenseStatus();
      log.info('License status', {
        plan: licenseStatus.plan,
        valid: licenseStatus.valid,
        maxUsers: licenseStatus.maxUsers,
        maxDevices: licenseStatus.maxDevices,
      });
      if (license) {
        startLicenseHeartbeat();
      }
    }

    // Ensure local storage directory exists when using local backend
    if (config.storage.backend === 'local' || (!config.r2.endpoint && config.storage.backend === 'auto')) {
      const storagePath = path.resolve(config.storage.localPath);
      await fs.mkdir(storagePath, { recursive: true });
      log.info('Local storage directory ready', { path: storagePath });
    }

    // Test database connection
    log.info('Connecting to PostgreSQL...');
    await checkDatabaseHealth();
    log.info('PostgreSQL connected');

    // Test Redis connection
    log.info('Connecting to Redis...');
    await checkRedisHealth();
    log.info('Redis connected');

    // Initialize FCM for push notifications
    initializeFCM();

    // Start HTTP server and attach WebSocket to it
    const server = http.createServer(app);
    const wss = createWebSocketServer(server);

    server.listen(config.port, () => {
      log.info('HTTP + WebSocket server listening', { port: config.port });
    });

    // Register graceful shutdown handler
    const gracefulShutdown = (signal: string) => {
      log.info('Shutdown signal received', { signal });
      stopDailyCleanup();
      stopLicenseHeartbeat();

      // Stop accepting new connections
      server.close(async () => {
        log.info('HTTP server closed, draining remaining connections...');
        try {
          // Close WebSocket server and all active connections
          wss.clients.forEach((ws) => ws.close(1001, 'Server shutting down'));
          wss.close();
          // Close database pool and Redis
          await pool.end();
          redis.disconnect();
          log.info('All connections closed. Exiting.');
          process.exit(0);
        } catch (err) {
          log.error('Error during shutdown cleanup', { error: (err as Error).message });
          process.exit(1);
        }
      });

      // Force shutdown after 30 seconds if draining takes too long
      setTimeout(() => {
        log.error('Graceful shutdown timed out after 30s, forcing exit.');
        process.exit(1);
      }, 30000);
    };

    process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
    process.on('SIGINT', () => gracefulShutdown('SIGINT'));

    // Start daily cleanup scheduler (3 AM UTC)
    startDailyCleanup();

    // Retry stale pending outgoing messages every 2 minutes
    setInterval(retryStaleOutgoingMessages, 2 * 60 * 1000);

    log.info('SyncFlow API Server started', {
      mode: config.nodeEnv,
      port: config.port,
      selfHosted: config.selfHosted,
      storageBackend: config.storage.backend,
      healthUrl: `http://localhost:${config.port}/health`,
    });

  } catch (error) {
    log.error('Failed to start server', { error: (error as Error).message, stack: (error as Error).stack });
    process.exit(1);
  }
}

// Start the server
start();
