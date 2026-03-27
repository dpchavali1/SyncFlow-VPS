import dotenv from 'dotenv';
dotenv.config();

export const config = {
  // Server
  port: parseInt(process.env.PORT || '3000', 10),
  wsPort: parseInt(process.env.WS_PORT || '3001', 10),
  nodeEnv: process.env.NODE_ENV || 'development',
  baseUrl: process.env.BASE_URL || `http://localhost:${process.env.PORT || '3000'}`,

  // Self-hosted mode
  selfHosted: process.env.SELF_HOSTED === 'true',

  // Storage backend ('r2' | 'local' | 'auto')
  storage: {
    backend: (process.env.STORAGE_BACKEND || 'auto') as 'r2' | 'local' | 'auto',
    localPath: process.env.LOCAL_STORAGE_PATH || './uploads',
  },

  // License (placeholder for future enforcement)
  license: {
    key: process.env.SYNCFLOW_LICENSE_KEY || '',
  },

  // PostgreSQL
  database: {
    host: process.env.PG_HOST || 'localhost',
    port: parseInt(process.env.PG_PORT || '5432', 10),
    user: process.env.PG_USER || 'syncflow',
    password: process.env.PG_PASSWORD || '',
    database: process.env.PG_DATABASE || 'syncflow_prod',
    max: 40, // max pool size
    idleTimeoutMillis: 30000,
    connectionTimeoutMillis: 2000,
  },

  // Redis
  redis: {
    host: process.env.REDIS_HOST || 'localhost',
    port: parseInt(process.env.REDIS_PORT || '6379', 10),
    password: process.env.REDIS_PASSWORD || '',
  },

  // JWT
  jwt: {
    secret: process.env.JWT_SECRET || (() => { throw new Error('JWT_SECRET environment variable is required'); })(),
    expiresIn: process.env.JWT_EXPIRES_IN || '7d',
    refreshExpiresIn: process.env.JWT_REFRESH_EXPIRES_IN || '30d',
  },

  // Rate Limiting
  rateLimit: {
    windowMs: parseInt(process.env.RATE_LIMIT_WINDOW_MS || '60000', 10),
    maxRequests: parseInt(process.env.RATE_LIMIT_MAX_REQUESTS || '100', 10),
  },

  // CORS
  corsOrigins: (process.env.CORS_ORIGINS || 'http://localhost:3000,http://localhost:3001,https://sfweb.app,https://www.sfweb.app').split(','),

  // Admin credentials (for VPS admin API)
  // ADMIN_PASSWORD MUST be a bcrypt hash: node -e "require('bcrypt').hash('yourpass',12).then(console.log)"
  admin: {
    username: process.env.ADMIN_USERNAME || 'admin',
    password: (() => {
      const pw = process.env.ADMIN_PASSWORD;
      if (!pw) throw new Error('ADMIN_PASSWORD environment variable is required');
      if (!pw.startsWith('$2')) console.warn('[SECURITY] ADMIN_PASSWORD should be a bcrypt hash, not plaintext. Generate one: node -e "require(\'bcrypt\').hash(\'yourpass\',12).then(console.log)"');
      return pw;
    })(),
    apiKey: process.env.ADMIN_API_KEY || '',
  },

  // Cloudflare R2 (S3-compatible storage)
  r2: {
    endpoint: process.env.R2_ENDPOINT || '',
    accessKeyId: process.env.R2_ACCESS_KEY_ID || '',
    secretAccessKey: process.env.R2_SECRET_ACCESS_KEY || '',
    bucketName: process.env.R2_BUCKET_NAME || 'syncflow-files',
  },

  // Email (Resend API for admin notifications)
  email: {
    resendApiKey: process.env.RESEND_API_KEY || '',
    adminEmail: process.env.ADMIN_EMAIL || '',
  },

  // Cloudflare TURN (WebRTC relay for video calling)
  cloudflare: {
    turnKeyId: process.env.CLOUDFLARE_TURN_KEY_ID || '',
    turnApiToken: process.env.CLOUDFLARE_TURN_API_TOKEN || '',
  },

  // FCM (Firebase Cloud Messaging for push notifications)
  fcm: {
    serviceAccountPath: process.env.FCM_SERVICE_ACCOUNT_PATH || '',
  },

  // Stripe (subscription billing)
  stripe: {
    secretKey: process.env.STRIPE_SECRET_KEY || '',
    webhookSecret: process.env.STRIPE_WEBHOOK_SECRET || '',
    priceMonthly: process.env.STRIPE_PRICE_MONTHLY || '',
    priceYearly: process.env.STRIPE_PRICE_YEARLY || '',
    price3Year: process.env.STRIPE_PRICE_3YEAR || '',
  },
};

// Startup warnings for missing optional security config
if (!config.admin.apiKey) {
  console.warn('[WARN] ADMIN_API_KEY is not set — admin API key auth will be disabled');
}

if (config.selfHosted) {
  console.log('[Self-Hosted] Running in self-hosted mode');
  if (!config.r2.endpoint) {
    console.log(`[Self-Hosted] R2 not configured — files will be stored locally at ${config.storage.localPath}`);
  }
  if (!config.fcm.serviceAccountPath) {
    console.log('[Self-Hosted] FCM not configured — push notifications disabled (WebSocket delivery only)');
  }
  if (!config.email.resendApiKey) {
    console.log('[Self-Hosted] Resend API not configured — admin emails will be logged to console');
  }
}
