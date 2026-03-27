// Photo sync feature has been removed.
// This file is kept as a stub to avoid import errors if referenced elsewhere.
// All photo sync endpoints have been removed.

import { Router, Request, Response } from 'express';

const router = Router();

// Return 410 Gone for any requests to photo endpoints
router.all('*', (_req: Request, res: Response) => {
  res.status(410).json({ error: 'Photo sync feature has been removed' });
});

export default router;
