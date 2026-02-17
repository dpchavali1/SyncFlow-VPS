/**
 * Admin sub-router: Database Browser.
 *
 * Generic CRUD for any PostgreSQL table. Table names are validated against
 * pg_tables / information_schema to prevent SQL injection. Primary keys are
 * detected dynamically from pg_index so updates/deletes work on any table.
 * Large text fields are truncated to 500 chars in browse results.
 *
 * Endpoints:
 *   GET    /tables                          — List all tables with row counts and sizes
 *   GET    /tables/:tableName/schema        — Column definitions (types, nullability, PK)
 *   GET    /tables/:tableName               — Browse table data (paginated, searchable, sortable)
 *   PUT    /tables/:tableName/:rowId        — Update a row (validates columns against schema)
 *   DELETE /tables/:tableName/:rowId        — Delete a row by primary key
 *   POST   /query                           — Run arbitrary SQL query (with destructive-op warning)
 *   GET    /db/health                       — DB health: size, connections, uptime
 *   GET    /sync-groups                     — List sync groups (user + device counts + plan)
 */

import { Router, Request, Response } from 'express';
import { query, queryOne } from '../../services/database';

const router = Router();

// ── GET /tables — List all tables with sizes ────────────────────────────────

router.get('/tables', async (req: Request, res: Response) => {
  try {
    const tables = await query<{
      table_name: string;
      estimated_rows: string;
      total_size: string;
      total_size_bytes: string;
    }>(`
      SELECT
        s.relname AS table_name,
        s.n_live_tup AS estimated_rows,
        pg_size_pretty(pg_total_relation_size(quote_ident(s.relname))) AS total_size,
        pg_total_relation_size(quote_ident(s.relname)) AS total_size_bytes
      FROM pg_stat_user_tables s
      ORDER BY pg_total_relation_size(quote_ident(s.relname)) DESC
    `);

    const dbSize = await queryOne<{ size: string; size_bytes: string }>(`
      SELECT
        pg_size_pretty(pg_database_size(current_database())) AS size,
        pg_database_size(current_database()) AS size_bytes
    `);

    res.json({
      tables: tables.map(t => ({
        name: t.table_name,
        estimatedRows: parseInt(t.estimated_rows),
        totalSize: t.total_size,
        totalSizeBytes: parseInt(t.total_size_bytes),
      })),
      databaseSize: dbSize?.size || 'unknown',
      databaseSizeBytes: parseInt(dbSize?.size_bytes || '0'),
    });
  } catch (error) {
    console.error('Admin tables error:', error);
    res.status(500).json({ error: 'Failed to fetch tables' });
  }
});

// ── GET /tables/:tableName/schema — Column definitions ──────────────────────

router.get('/tables/:tableName/schema', async (req: Request, res: Response) => {
  try {
    const { tableName } = req.params;

    // Validate table exists (SQL injection prevention — tableName is parameterized)
    const tableExists = await queryOne<{ exists: boolean }>(`
      SELECT EXISTS (
        SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = $1
      ) AS exists
    `, [tableName]);

    if (!tableExists?.exists) {
      res.status(404).json({ error: 'Table not found' });
      return;
    }

    const columns = await query<{
      column_name: string;
      data_type: string;
      is_nullable: string;
      column_default: string | null;
      character_maximum_length: number | null;
    }>(`
      SELECT column_name, data_type, is_nullable, column_default, character_maximum_length
      FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = $1
      ORDER BY ordinal_position
    `, [tableName]);

    // Get primary key columns
    const pkColumns = await query<{ column_name: string }>(`
      SELECT kcu.column_name
      FROM information_schema.table_constraints tc
      JOIN information_schema.key_column_usage kcu
        ON tc.constraint_name = kcu.constraint_name
        AND tc.table_schema = kcu.table_schema
      WHERE tc.constraint_type = 'PRIMARY KEY'
        AND tc.table_schema = 'public'
        AND tc.table_name = $1
    `, [tableName]);

    const pkSet = new Set(pkColumns.map(pk => pk.column_name));

    res.json({
      tableName,
      columns: columns.map(c => ({
        name: c.column_name,
        type: c.data_type,
        nullable: c.is_nullable === 'YES',
        default: c.column_default,
        maxLength: c.character_maximum_length,
        isPrimaryKey: pkSet.has(c.column_name),
      })),
    });
  } catch (error) {
    console.error('Admin table schema error:', error);
    res.status(500).json({ error: 'Failed to fetch table schema' });
  }
});

// ── GET /tables/:tableName — Browse table data (paginated) ──────────────────

router.get('/tables/:tableName', async (req: Request, res: Response) => {
  try {
    const { tableName } = req.params;

    // Validate table exists
    const tableExists = await queryOne<{ exists: boolean }>(`
      SELECT EXISTS (
        SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = $1
      ) AS exists
    `, [tableName]);

    if (!tableExists?.exists) {
      res.status(404).json({ error: 'Table not found' });
      return;
    }

    const limit = Math.min(parseInt(req.query.limit as string) || 50, 200);
    const offset = parseInt(req.query.offset as string) || 0;
    const sort = req.query.sort as string | undefined;
    const order = (req.query.order as string)?.toUpperCase() === 'ASC' ? 'ASC' : 'DESC';
    const search = req.query.search as string | undefined;

    // Get column names for validation and search
    const colResult = await query<{ column_name: string; data_type: string }>(`
      SELECT column_name, data_type FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = $1
      ORDER BY ordinal_position
    `, [tableName]);

    const columnNames = colResult.map(c => c.column_name);
    const textColumns = colResult
      .filter(c => ['text', 'character varying', 'varchar', 'char', 'uuid'].includes(c.data_type))
      .map(c => c.column_name);

    // Validate sort column against actual schema columns
    const sortColumn = sort && columnNames.includes(sort) ? sort : null;

    // Build query
    let sql = `SELECT * FROM ${tableName}`;
    const params: any[] = [];

    // Search across text columns
    if (search && textColumns.length > 0) {
      const searchConditions = textColumns.map((col, i) => {
        params.push(`%${search}%`);
        return `${col}::text ILIKE $${params.length}`;
      });
      sql += ` WHERE (${searchConditions.join(' OR ')})`;
    }

    if (sortColumn) {
      sql += ` ORDER BY ${sortColumn} ${order}`;
    }

    params.push(limit);
    sql += ` LIMIT $${params.length}`;
    params.push(offset);
    sql += ` OFFSET $${params.length}`;

    const rows = await query(sql, params);

    // Get total count (with same search filter)
    let countSql = `SELECT COUNT(*) AS count FROM ${tableName}`;
    const countParams: any[] = [];
    if (search && textColumns.length > 0) {
      const searchConditions = textColumns.map((col) => {
        countParams.push(`%${search}%`);
        return `${col}::text ILIKE $${countParams.length}`;
      });
      countSql += ` WHERE (${searchConditions.join(' OR ')})`;
    }
    const totalResult = await queryOne<{ count: string }>(countSql, countParams);

    // Truncate large text fields at 500 chars for readability
    const truncatedRows = rows.map((row: any) => {
      const truncated: any = {};
      for (const [key, value] of Object.entries(row)) {
        if (typeof value === 'string' && value.length > 500) {
          truncated[key] = value.substring(0, 500) + '...';
        } else {
          truncated[key] = value;
        }
      }
      return truncated;
    });

    res.json({
      tableName,
      columns: columnNames,
      rows: truncatedRows,
      total: parseInt(totalResult?.count || '0'),
      limit,
      offset,
    });
  } catch (error) {
    console.error('Admin table data error:', error);
    res.status(500).json({ error: 'Failed to fetch table data' });
  }
});

// ── PUT /tables/:tableName/:rowId — Update a row ────────────────────────────
// Validates column names against information_schema, detects PK from pg_index.

router.put('/tables/:tableName/:rowId', async (req: Request, res: Response) => {
  try {
    const { tableName, rowId } = req.params;
    const updates = req.body;

    // Validate table exists
    const tables = await query(
      `SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name = $1`,
      [tableName]
    );
    if (tables.length === 0) {
      res.status(404).json({ error: 'Table not found' });
      return;
    }

    // Detect primary key column from pg_index
    const pkResult = await query(
      `SELECT a.attname FROM pg_index i
       JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
       WHERE i.indrelid = $1::regclass AND i.indisprimary`,
      [tableName]
    );

    const pkColumn = pkResult.length > 0 ? pkResult[0].attname : 'id';

    // Fetch actual column names for validation
    const tableColumns = await query<{ column_name: string }>(
      `SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = $1`,
      [tableName]
    );
    const validColumns = new Set(tableColumns.map(c => c.column_name));

    // Build parameterized UPDATE query
    const setClauses: string[] = [];
    const values: any[] = [];
    let paramIdx = 1;

    for (const [col, val] of Object.entries(updates)) {
      if (col === pkColumn) continue; // Don't update PK
      if (!validColumns.has(col)) {
        res.status(400).json({ error: `Invalid column: ${col}` });
        return;
      }
      setClauses.push(`"${col}" = $${paramIdx++}`);
      values.push(val);
    }

    if (setClauses.length === 0) {
      res.status(400).json({ error: 'No columns to update' });
      return;
    }

    values.push(rowId);
    const result = await query(
      `UPDATE "${tableName}" SET ${setClauses.join(', ')} WHERE "${pkColumn}" = $${paramIdx} RETURNING *`,
      values
    );

    if (result.length === 0) {
      res.status(404).json({ error: 'Row not found' });
      return;
    }

    res.json({ success: true, row: result[0] });
  } catch (error) {
    console.error('Update row error:', error);
    res.status(500).json({ error: 'Failed to update row' });
  }
});

// ── DELETE /tables/:tableName/:rowId — Delete a row ─────────────────────────

router.delete('/tables/:tableName/:rowId', async (req: Request, res: Response) => {
  try {
    const { tableName, rowId } = req.params;

    // Validate table exists
    const tables = await query(
      `SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name = $1`,
      [tableName]
    );
    if (tables.length === 0) {
      res.status(404).json({ error: 'Table not found' });
      return;
    }

    // Detect primary key column
    const pkResult = await query(
      `SELECT a.attname FROM pg_index i
       JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
       WHERE i.indrelid = $1::regclass AND i.indisprimary`,
      [tableName]
    );

    const pkColumn = pkResult.length > 0 ? pkResult[0].attname : 'id';

    const result = await query(
      `DELETE FROM "${tableName}" WHERE "${pkColumn}" = $1 RETURNING *`,
      [rowId]
    );

    if (result.length === 0) {
      res.status(404).json({ error: 'Row not found' });
      return;
    }

    res.json({ success: true });
  } catch (error) {
    console.error('Delete row error:', error);
    res.status(500).json({ error: 'Failed to delete row' });
  }
});

// ── POST /query — Run arbitrary SQL ─────────────────────────────────────────

router.post('/query', async (req: Request, res: Response) => {
  try {
    const { sql } = req.body;

    if (!sql || typeof sql !== 'string') {
      res.status(400).json({ error: 'SQL query is required' });
      return;
    }

    // Flag destructive queries for client-side awareness
    const normalized = sql.trim().toLowerCase();
    const isModifying = normalized.startsWith('update') || normalized.startsWith('delete') ||
                        normalized.startsWith('insert') || normalized.startsWith('drop') ||
                        normalized.startsWith('alter') || normalized.startsWith('truncate');

    const result = await query(sql);

    res.json({
      rows: Array.isArray(result) ? result : [],
      rowCount: Array.isArray(result) ? result.length : 0,
      isModifying,
    });
  } catch (error: any) {
    res.status(400).json({ error: error.message || 'Query failed' });
  }
});

// ── GET /db/health — Database health check ──────────────────────────────────

router.get('/db/health', async (req: Request, res: Response) => {
  try {
    const health = await queryOne<{
      db_size: string;
      active_connections: string;
      max_connections: string;
      uptime: string;
    }>(`
      SELECT
        pg_size_pretty(pg_database_size(current_database())) AS db_size,
        (SELECT count(*) FROM pg_stat_activity WHERE state = 'active') AS active_connections,
        (SELECT setting FROM pg_settings WHERE name = 'max_connections') AS max_connections,
        (SELECT date_trunc('second', current_timestamp - pg_postmaster_start_time()))::text AS uptime
    `);

    res.json({
      dbSize: health?.db_size || 'unknown',
      activeConnections: parseInt(health?.active_connections || '0'),
      maxConnections: parseInt(health?.max_connections || '100'),
      uptime: health?.uptime || 'unknown',
    });
  } catch (error) {
    console.error('Admin db health error:', error);
    res.status(500).json({ error: 'Failed to fetch database health' });
  }
});

// ── GET /sync-groups — List sync groups (user + devices + plan) ─────────────
// In SyncFlow, each user is effectively a sync group (one user → multiple devices).

router.get('/sync-groups', async (req: Request, res: Response) => {
  try {
    const groups = await query<{
      uid: string;
      phone: string | null;
      created_at: Date;
      device_count: string;
    }>(`
      SELECT
        u.uid,
        u.phone,
        u.created_at,
        (SELECT COUNT(*) FROM user_devices d WHERE d.user_id = u.uid) AS device_count
      FROM users u
      ORDER BY u.created_at DESC
    `);

    // Get subscription info
    const subs = await query<{ user_id: string; plan: string }>(`
      SELECT user_id, plan FROM user_subscriptions
    `);
    const subMap = new Map(subs.map(s => [s.user_id, s.plan]));

    res.json({
      success: true,
      groups: groups.map(g => ({
        syncGroupId: g.uid,
        plan: subMap.get(g.uid) || 'free',
        deviceCount: parseInt(g.device_count),
        deviceLimit: (subMap.get(g.uid) || 'free') === 'free' ? 2 : 10,
        createdAt: new Date(g.created_at).getTime(),
        masterDevice: g.phone || g.uid,
      })),
    });
  } catch (error) {
    console.error('Admin sync-groups error:', error);
    res.status(500).json({ error: 'Failed to fetch sync groups' });
  }
});

export default router;
