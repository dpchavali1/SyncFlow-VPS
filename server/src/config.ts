import dotenv from 'dotenv';
dotenv.config();

export const config = {
  // Server
  port: parseInt(process.env.PORT || '3000', 10),
  wsPort: parseInt(process.env.WS_PORT || '3001', 10),
  nodeEnv: process.env.NODE_ENV || 'development',

  // PostgreSQL
  database: {
    host: process.env.PG_HOST || 'localhost',
    port: parseInt(process.env.PG_PORT || '5432', 10),
    user: process.env.PG_USER || 'syncflow',
    password: process.env.PG_PASSWORD || '',
    database: process.env.PG_DATABASE || 'syncflow_prod',
    max: 20, // max pool size
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
    secret: process.env.JWT_SECRET || 'change-this-in-production',
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
  admin: {
    username: process.env.ADMIN_USERNAME || 'admin',
    password: process.env.ADMIN_PASSWORD || 'Dxc@Abc+123=007',
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
    priceLifetime: process.env.STRIPE_PRICE_LIFETIME || '',
  },
};
