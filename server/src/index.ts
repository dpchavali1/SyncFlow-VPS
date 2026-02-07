import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import http from 'http';
import { config } from './config';
import { pool, checkDatabaseHealth } from './services/database';
import { redis, checkRedisHealth } from './services/redis';
import { createWebSocketServer } from './services/websocket';
import { initLogger } from './services/logger';
import { maintenanceMiddleware } from './middleware/maintenance';

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
import photosRoutes from './routes/photos';
import usageRoutes from './routes/usage';

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

// Body parsing (skip JSON parsing for Stripe webhook which needs raw body)
app.use((req, res, next) => {
  if (req.path === '/api/usage/subscription/webhook') {
    next();
  } else {
    express.json({ limit: '10mb' })(req, res, next);
  }
});
app.use(express.urlencoded({ extended: true }));

// Request logging (simple)
app.use((req, res, next) => {
  const start = Date.now();
  res.on('finish', () => {
    const duration = Date.now() - start;
    if (config.nodeEnv === 'development' || duration > 1000) {
      console.log(`${req.method} ${req.path} ${res.statusCode} ${duration}ms`);
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
  });
});

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
app.use('/api/photos', photosRoutes);
app.use('/api/usage', usageRoutes);

// 404 handler
app.use((req, res) => {
  res.status(404).json({ error: 'Not found' });
});

// Error handler
app.use((err: Error, req: express.Request, res: express.Response, next: express.NextFunction) => {
  console.error('Unhandled error:', err);
  res.status(500).json({ error: 'Internal server error' });
});

// Start servers
async function start() {
  try {
    // Test database connection
    console.log('Connecting to PostgreSQL...');
    await checkDatabaseHealth();
    console.log('PostgreSQL connected');

    // Test Redis connection
    console.log('Connecting to Redis...');
    await checkRedisHealth();
    console.log('Redis connected');

    // Start HTTP server and attach WebSocket to it
    const server = http.createServer(app);
    createWebSocketServer(server);

    server.listen(config.port, () => {
      console.log(`HTTP + WebSocket server listening on port ${config.port}`);
    });

    console.log(`\nSyncFlow API Server started in ${config.nodeEnv} mode`);
    console.log(`- HTTP + WS: http://localhost:${config.port}`);
    console.log(`- Health:    http://localhost:${config.port}/health`);

  } catch (error) {
    console.error('Failed to start server:', error);
    process.exit(1);
  }
}

// Graceful shutdown
process.on('SIGTERM', async () => {
  console.log('SIGTERM received, shutting down...');
  await pool.end();
  redis.disconnect();
  process.exit(0);
});

process.on('SIGINT', async () => {
  console.log('SIGINT received, shutting down...');
  await pool.end();
  redis.disconnect();
  process.exit(0);
});

// Start the server
start();
