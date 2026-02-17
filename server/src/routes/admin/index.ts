/**
 * Admin API router — entry point.
 *
 * Mounts all admin sub-routers under a single Express Router, applying shared
 * middleware (JWT authentication, admin role check, rate limiting) once at the
 * top level so individual sub-routers don't need to repeat it.
 *
 * Sub-routers:
 *   /admin/users, /admin/user-lookup      → users.ts      (User CRUD, plans, storage)
 *   /admin/devices, /admin/pairing-*       → devices.ts    (Device listing, pairing)
 *   /admin/cleanup/*, /admin/orphans, …    → cleanup.ts    (Cleanup, orphans, duplicates, deletions)
 *   /admin/r2/*                            → r2.ts         (R2 storage management)
 *   /admin/analytics/*, /admin/stats, …    → analytics.ts  (Analytics, stats, overview, costs)
 *   /admin/tables/*, /admin/query, …       → database.ts   (DB browser, SQL query, health)
 *   /admin/crashes, /admin/sessions, …     → misc.ts       (Crashes, sessions, E2EE, logs, alerts, maintenance)
 */

import { Router } from 'express';
import { authenticate, requireAdmin } from '../../middleware/auth';
import { adminRateLimit } from '../../middleware/rateLimit';

import usersRouter from './users';
import devicesRouter from './devices';
import cleanupRouter from './cleanup';
import r2Router from './r2';
import analyticsRouter from './analytics';
import databaseRouter from './database';
import miscRouter from './misc';

const router = Router();

// ── Shared middleware — applies to every sub-router ─────────────────────────
router.use(authenticate);
router.use(requireAdmin);
router.use(adminRateLimit);

// ── Mount sub-routers ───────────────────────────────────────────────────────
router.use(usersRouter);
router.use(devicesRouter);
router.use(cleanupRouter);
router.use(r2Router);
router.use(analyticsRouter);
router.use(databaseRouter);
router.use(miscRouter);

export default router;
