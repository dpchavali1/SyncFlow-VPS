import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { query } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';
import { normalizePhoneNumber } from '../utils/phoneNumber';

const router = Router();

router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const syncContactSchema = z.object({
  id: z.string().min(1),
  displayName: z.string().max(255).optional(),
  phoneNumbers: z.array(z.string()).optional(),
  emails: z.array(z.string()).optional(),
  photoThumbnail: z.string().max(200000).optional(), // Base64, max ~150KB
});

const syncContactsSchema = z.object({
  contacts: z.array(syncContactSchema).max(1000),
});

// GET /contacts - Get all contacts
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;
    const { search, limit = 500 } = req.query;

    let queryText = `
      SELECT id, display_name, phone_numbers, emails, photo_thumbnail
      FROM user_contacts
      WHERE user_id = $1
    `;
    const params: any[] = [userId];
    let paramIndex = 2;

    if (search && typeof search === 'string') {
      queryText += ` AND (display_name ILIKE $${paramIndex} OR phone_numbers::text ILIKE $${paramIndex})`;
      params.push(`%${search}%`);
      paramIndex++;
    }

    queryText += ` ORDER BY display_name ASC NULLS LAST LIMIT $${paramIndex}`;
    params.push(Math.min(Number(limit), 1000));

    const contacts = await query(queryText, params);

    res.json({
      contacts: contacts.map((c) => ({
        id: c.id,
        displayName: c.display_name,
        phoneNumbers: c.phone_numbers || [],
        emails: c.emails || [],
        photoThumbnail: c.photo_thumbnail,
      })),
    });
  } catch (error) {
    console.error('Get contacts error:', error);
    res.status(500).json({ error: 'Failed to get contacts' });
  }
});

// POST /contacts/sync - Sync contacts from device
router.post('/sync', async (req: Request, res: Response) => {
  try {
    const body = syncContactsSchema.parse(req.body);
    const userId = req.userId!;

    let synced = 0;
    let skipped = 0;

    for (const contact of body.contacts) {
      try {
        await query(
          `INSERT INTO user_contacts
           (id, user_id, display_name, phone_numbers, emails, photo_thumbnail)
           VALUES ($1, $2, $3, $4, $5, $6)
           ON CONFLICT (id) DO UPDATE SET
             display_name = EXCLUDED.display_name,
             phone_numbers = EXCLUDED.phone_numbers,
             emails = EXCLUDED.emails,
             photo_thumbnail = EXCLUDED.photo_thumbnail,
             updated_at = NOW()`,
          [
            contact.id,
            userId,
            contact.displayName,
            JSON.stringify((contact.phoneNumbers || []).map((p: string) => normalizePhoneNumber(p))),
            JSON.stringify(contact.emails || []),
            contact.photoThumbnail,
          ]
        );
        synced++;
      } catch (e) {
        skipped++;
      }
    }

    res.json({ synced, skipped, total: body.contacts.length });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Sync contacts error:', error);
    res.status(500).json({ error: 'Failed to sync contacts' });
  }
});

// GET /contacts/:id - Get single contact
router.get('/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    const contacts = await query(
      `SELECT id, display_name, phone_numbers, emails, photo_thumbnail
       FROM user_contacts
       WHERE id = $1 AND user_id = $2`,
      [id, userId]
    );

    if (contacts.length === 0) {
      res.status(404).json({ error: 'Contact not found' });
      return;
    }

    const c = contacts[0];
    res.json({
      id: c.id,
      displayName: c.display_name,
      phoneNumbers: c.phone_numbers || [],
      emails: c.emails || [],
      photoThumbnail: c.photo_thumbnail,
    });
  } catch (error) {
    console.error('Get contact error:', error);
    res.status(500).json({ error: 'Failed to get contact' });
  }
});

// PUT /contacts/:id - Update a contact
router.put('/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;
    const { displayName, phoneNumbers, emails, photoThumbnail } = req.body;

    const setClauses: string[] = ['updated_at = NOW()'];
    const params: any[] = [id, userId];
    let paramIndex = 3;

    if (displayName !== undefined) {
      setClauses.push(`display_name = $${paramIndex++}`);
      params.push(displayName);
    }
    if (phoneNumbers !== undefined) {
      setClauses.push(`phone_numbers = $${paramIndex++}`);
      params.push(JSON.stringify(phoneNumbers));
    }
    if (emails !== undefined) {
      setClauses.push(`emails = $${paramIndex++}`);
      params.push(JSON.stringify(emails));
    }
    if (photoThumbnail !== undefined) {
      setClauses.push(`photo_thumbnail = $${paramIndex++}`);
      params.push(photoThumbnail);
    }

    const result = await query(
      `UPDATE user_contacts SET ${setClauses.join(', ')} WHERE id = $1 AND user_id = $2 RETURNING id`,
      params
    );

    if (result.length === 0) {
      res.status(404).json({ error: 'Contact not found' });
      return;
    }

    res.json({ success: true });
  } catch (error) {
    console.error('Update contact error:', error);
    res.status(500).json({ error: 'Failed to update contact' });
  }
});

// DELETE /contacts/:id - Delete a contact
router.delete('/:id', async (req: Request, res: Response) => {
  try {
    const { id } = req.params;
    const userId = req.userId!;

    const result = await query(
      `DELETE FROM user_contacts WHERE id = $1 AND user_id = $2 RETURNING id`,
      [id, userId]
    );

    if (result.length === 0) {
      res.status(404).json({ error: 'Contact not found' });
      return;
    }

    res.json({ success: true });
  } catch (error) {
    console.error('Delete contact error:', error);
    res.status(500).json({ error: 'Failed to delete contact' });
  }
});

// GET /contacts/count - Get contact count
router.get('/meta/count', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const result = await query(
      `SELECT COUNT(*) as count FROM user_contacts WHERE user_id = $1`,
      [userId]
    );

    res.json({ count: parseInt(result[0]?.count || '0') });
  } catch (error) {
    console.error('Get contact count error:', error);
    res.status(500).json({ error: 'Failed to get count' });
  }
});

export default router;
