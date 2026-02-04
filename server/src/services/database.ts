import { Pool, PoolClient } from 'pg';
import { config } from '../config';

// Create connection pool
export const pool = new Pool({
  host: config.database.host,
  port: config.database.port,
  user: config.database.user,
  password: config.database.password,
  database: config.database.database,
  max: config.database.max,
  idleTimeoutMillis: config.database.idleTimeoutMillis,
  connectionTimeoutMillis: config.database.connectionTimeoutMillis,
});

// Log pool errors
pool.on('error', (err) => {
  console.error('Unexpected database pool error:', err);
});

// Helper for single queries
export async function query<T = any>(
  text: string,
  params?: any[]
): Promise<T[]> {
  const start = Date.now();
  const result = await pool.query(text, params);
  const duration = Date.now() - start;

  if (config.nodeEnv === 'development') {
    console.log('Query executed', { text: text.substring(0, 50), duration, rows: result.rowCount });
  }

  return result.rows as T[];
}

// Helper for single row queries
export async function queryOne<T = any>(
  text: string,
  params?: any[]
): Promise<T | null> {
  const rows = await query<T>(text, params);
  return rows[0] || null;
}

// Transaction helper
export async function transaction<T>(
  callback: (client: PoolClient) => Promise<T>
): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await callback(client);
    await client.query('COMMIT');
    return result;
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

// Health check
export async function checkDatabaseHealth(): Promise<boolean> {
  try {
    await query('SELECT 1');
    return true;
  } catch {
    return false;
  }
}

// LISTEN/NOTIFY for real-time updates
export async function listenToChannel(
  channel: string,
  callback: (payload: string) => void
): Promise<PoolClient> {
  const client = await pool.connect();

  client.on('notification', (msg) => {
    if (msg.channel === channel && msg.payload) {
      callback(msg.payload);
    }
  });

  await client.query(`LISTEN ${channel}`);
  return client;
}
