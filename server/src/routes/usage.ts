import { Router, Request, Response } from 'express';
import express from 'express';
import { z } from 'zod';
import { query, queryOne } from '../services/database';
import { authenticate } from '../middleware/auth';
import { apiRateLimit } from '../middleware/rateLimit';
import { config } from '../config';

const router = Router();

// Conditionally load Stripe (don't crash if package not installed)
let stripe: any = null;
try {
  if (config.stripe.secretKey) {
    const Stripe = require('stripe').default || require('stripe');
    stripe = new Stripe(config.stripe.secretKey);
  }
} catch (e) {
  console.log('[Usage] Stripe package not installed - billing endpoints disabled');
}

// Helper to check if Stripe is configured
function requireStripe(res: Response): res is Response {
  if (!stripe) {
    res.status(501).json({ error: 'Stripe is not configured' });
    return false;
  }
  return true;
}

// ---- Webhook endpoint (BEFORE authenticate middleware, uses raw body) ----
router.post(
  '/subscription/webhook',
  express.raw({ type: 'application/json' }),
  async (req: Request, res: Response) => {
    if (!requireStripe(res)) return;

    const sig = req.headers['stripe-signature'] as string;
    if (!sig) {
      res.status(400).json({ error: 'Missing stripe-signature header' });
      return;
    }

    let event: any;
    try {
      event = stripe!.webhooks.constructEvent(
        req.body,
        sig,
        config.stripe.webhookSecret
      );
    } catch (err: any) {
      console.error('Stripe webhook signature verification failed:', err.message);
      res.status(400).json({ error: `Webhook signature verification failed: ${err.message}` });
      return;
    }

    try {
      switch (event.type) {
        case 'checkout.session.completed': {
          const session = event.data.object as any;
          const userId = session.metadata?.userId;
          const plan = session.metadata?.plan || 'pro_monthly';
          const customerId = session.customer as string;
          const subscriptionId = session.subscription as string;

          if (!userId) {
            console.error('Stripe webhook: No userId in session metadata');
            break;
          }

          // Calculate expiration based on plan
          let expiresAt: string;
          if (plan === 'lifetime') {
            // Lifetime plan: set expiry far in the future
            expiresAt = new Date(Date.now() + 100 * 365 * 24 * 60 * 60 * 1000).toISOString();
          } else if (plan === 'yearly' || plan === 'pro_yearly') {
            expiresAt = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString();
          } else {
            // monthly
            expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
          }

          await query(
            `INSERT INTO user_subscriptions (user_id, plan, status, started_at, expires_at, stripe_customer_id, stripe_subscription_id)
             VALUES ($1, $2, 'active', NOW(), $3, $4, $5)
             ON CONFLICT (user_id) DO UPDATE SET
               plan = $2,
               status = 'active',
               started_at = NOW(),
               expires_at = $3,
               stripe_customer_id = $4,
               stripe_subscription_id = $5`,
            [userId, plan, expiresAt, customerId, subscriptionId || null]
          );

          console.log(`Subscription activated for user ${userId}: plan=${plan}`);
          break;
        }

        case 'customer.subscription.updated': {
          const subscription = event.data.object as any;
          const status = subscription.status === 'active' ? 'active' : 'past_due';
          // In newer Stripe API versions, current_period_end is on subscription items
          const firstItem = subscription.items?.data?.[0];
          const currentPeriodEnd = firstItem?.current_period_end
            ? new Date(firstItem.current_period_end * 1000).toISOString()
            : new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

          await query(
            `UPDATE user_subscriptions
             SET status = $1, expires_at = $2
             WHERE stripe_subscription_id = $3`,
            [status, currentPeriodEnd, subscription.id]
          );

          console.log(`Subscription updated: ${subscription.id} -> ${status}`);
          break;
        }

        case 'customer.subscription.deleted': {
          const subscription = event.data.object as any;

          await query(
            `UPDATE user_subscriptions
             SET status = 'cancelled', plan = 'free'
             WHERE stripe_subscription_id = $1`,
            [subscription.id]
          );

          console.log(`Subscription cancelled: ${subscription.id}`);
          break;
        }

        case 'invoice.payment_failed': {
          const invoice = event.data.object as any;
          // In newer Stripe API, subscription is accessed via parent.subscription_details
          const subscriptionRef = invoice.parent?.subscription_details?.subscription;
          const subscriptionId = typeof subscriptionRef === 'string'
            ? subscriptionRef
            : subscriptionRef?.id || null;

          if (subscriptionId) {
            await query(
              `UPDATE user_subscriptions
               SET status = 'past_due'
               WHERE stripe_subscription_id = $1`,
              [subscriptionId]
            );

            console.log(`Payment failed for subscription: ${subscriptionId}`);
          }
          break;
        }

        default:
          console.log(`Unhandled Stripe event type: ${event.type}`);
      }

      res.json({ received: true });
    } catch (error) {
      console.error('Stripe webhook handler error:', error);
      res.status(500).json({ error: 'Webhook handler failed' });
    }
  }
);

// ---- Apply authentication and rate limiting to all subsequent routes ----
router.use(authenticate);
router.use(apiRateLimit);

// Validation schemas
const recordUsageSchema = z.object({
  bytes: z.number(),
  category: z.string(), // mms, file, photo
  countsTowardStorage: z.boolean().default(true),
});

// GET /usage - Get user usage
router.get('/', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    // Get usage data
    const usage = await queryOne(
      `SELECT storage_bytes, bandwidth_bytes_month, message_count, last_reset, updated_at
       FROM user_usage
       WHERE user_id = $1`,
      [userId]
    );

    // Get subscription data
    const subscription = await queryOne(
      `SELECT plan, status, started_at, expires_at
       FROM user_subscriptions
       WHERE user_id = $1`,
      [userId]
    );

    // Calculate message counts
    const msgCount = await queryOne(
      `SELECT COUNT(*) as count FROM user_messages WHERE user_id = $1`,
      [userId]
    );

    const contactCount = await queryOne(
      `SELECT COUNT(*) as count FROM user_contacts WHERE user_id = $1`,
      [userId]
    );

    res.json({
      success: true,
      usage: {
        plan: subscription?.plan || 'free',
        planExpiresAt: subscription?.expires_at
          ? new Date(subscription.expires_at).getTime()
          : null,
        trialStartedAt: subscription?.started_at
          ? new Date(subscription.started_at).getTime()
          : null,
        storageBytes: parseInt(usage?.storage_bytes || '0'),
        monthlyUploadBytes: parseInt(usage?.bandwidth_bytes_month || '0'),
        monthlyMmsBytes: 0, // TODO: Track separately
        monthlyFileBytes: 0, // TODO: Track separately
        messageCount: parseInt(msgCount?.count || '0'),
        contactCount: parseInt(contactCount?.count || '0'),
        lastUpdatedAt: usage?.updated_at
          ? new Date(usage.updated_at).getTime()
          : null,
      },
    });
  } catch (error) {
    console.error('Get usage error:', error);
    res.status(500).json({ error: 'Failed to get usage' });
  }
});

// POST /usage/record - Record usage
router.post('/record', async (req: Request, res: Response) => {
  try {
    const body = recordUsageSchema.parse(req.body);
    const userId = req.userId!;

    await query(
      `INSERT INTO user_usage (user_id, storage_bytes, bandwidth_bytes_month, updated_at)
       VALUES ($1, $2, $2, NOW())
       ON CONFLICT (user_id) DO UPDATE SET
         storage_bytes = CASE WHEN $3 THEN user_usage.storage_bytes + $2 ELSE user_usage.storage_bytes END,
         bandwidth_bytes_month = user_usage.bandwidth_bytes_month + $2,
         updated_at = NOW()`,
      [userId, body.bytes, body.countsTowardStorage]
    );

    res.json({ success: true });
  } catch (error) {
    if (error instanceof z.ZodError) {
      res.status(400).json({ error: 'Invalid request', details: error.errors });
      return;
    }
    console.error('Record usage error:', error);
    res.status(500).json({ error: 'Failed to record usage' });
  }
});

// POST /usage/reset-storage - Reset storage usage
router.post('/reset-storage', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    await query(
      `UPDATE user_usage SET storage_bytes = 0, updated_at = NOW()
       WHERE user_id = $1`,
      [userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Reset storage usage error:', error);
    res.status(500).json({ error: 'Failed to reset storage usage' });
  }
});

// POST /usage/reset-monthly - Reset monthly usage
router.post('/reset-monthly', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    await query(
      `UPDATE user_usage SET bandwidth_bytes_month = 0, last_reset = NOW(), updated_at = NOW()
       WHERE user_id = $1`,
      [userId]
    );

    res.json({ success: true });
  } catch (error) {
    console.error('Reset monthly usage error:', error);
    res.status(500).json({ error: 'Failed to reset monthly usage' });
  }
});

// GET /subscription - Get subscription status
router.get('/subscription', async (req: Request, res: Response) => {
  try {
    const userId = req.userId!;

    const subscription = await queryOne(
      `SELECT plan, status, started_at, expires_at, stripe_customer_id, stripe_subscription_id
       FROM user_subscriptions
       WHERE user_id = $1`,
      [userId]
    );

    if (!subscription) {
      res.json({
        plan: 'free',
        status: 'active',
        features: {
          maxDevices: 2,
          maxFileSize: 50 * 1024 * 1024, // 50MB
          photoSync: false,
          mediaControl: false,
        },
      });
      return;
    }

    const isPro = subscription.plan !== 'free' && subscription.status === 'active';

    res.json({
      plan: subscription.plan,
      status: subscription.status,
      startedAt: subscription.started_at ? new Date(subscription.started_at).getTime() : null,
      expiresAt: subscription.expires_at ? new Date(subscription.expires_at).getTime() : null,
      features: {
        maxDevices: isPro ? 10 : 2,
        maxFileSize: isPro ? 1024 * 1024 * 1024 : 50 * 1024 * 1024, // 1GB vs 50MB
        photoSync: isPro,
        mediaControl: isPro,
      },
    });
  } catch (error) {
    console.error('Get subscription error:', error);
    res.status(500).json({ error: 'Failed to get subscription' });
  }
});

// POST /subscription/checkout - Create a Stripe checkout session
router.post('/subscription/checkout', async (req: Request, res: Response) => {
  if (!requireStripe(res)) return;

  try {
    const userId = req.userId!;
    const { plan } = req.body;

    if (!plan || !['monthly', 'yearly', 'lifetime'].includes(plan)) {
      res.status(400).json({ error: 'Invalid plan. Must be one of: monthly, yearly, lifetime' });
      return;
    }

    // Map plan to Stripe price ID
    const priceMap: Record<string, string> = {
      monthly: config.stripe.priceMonthly,
      yearly: config.stripe.priceYearly,
      lifetime: config.stripe.priceLifetime,
    };

    const priceId = priceMap[plan];
    if (!priceId) {
      res.status(400).json({ error: `Price not configured for plan: ${plan}` });
      return;
    }

    // Check if user already has a Stripe customer ID
    const existing = await queryOne(
      `SELECT stripe_customer_id FROM user_subscriptions WHERE user_id = $1`,
      [userId]
    );

    const sessionParams: any = {
      mode: plan === 'lifetime' ? 'payment' : 'subscription',
      payment_method_types: ['card'],
      line_items: [
        {
          price: priceId,
          quantity: 1,
        },
      ],
      metadata: {
        userId,
        plan: plan === 'lifetime' ? 'lifetime' : `pro_${plan}`,
      },
      success_url: `${config.corsOrigins[0]}/settings?subscription=success`,
      cancel_url: `${config.corsOrigins[0]}/settings?subscription=cancelled`,
    };

    // Reuse existing customer if available
    if (existing?.stripe_customer_id) {
      sessionParams.customer = existing.stripe_customer_id;
    }

    const session = await stripe!.checkout.sessions.create(sessionParams);

    res.json({ url: session.url });
  } catch (error) {
    console.error('Create checkout session error:', error);
    res.status(500).json({ error: 'Failed to create checkout session' });
  }
});

// GET /subscription/portal - Get Stripe customer portal URL
router.get('/subscription/portal', async (req: Request, res: Response) => {
  if (!requireStripe(res)) return;

  try {
    const userId = req.userId!;

    const subscription = await queryOne(
      `SELECT stripe_customer_id FROM user_subscriptions WHERE user_id = $1`,
      [userId]
    );

    if (!subscription?.stripe_customer_id) {
      res.status(404).json({ error: 'No billing account found. Subscribe to a plan first.' });
      return;
    }

    const portalSession = await stripe!.billingPortal.sessions.create({
      customer: subscription.stripe_customer_id,
      return_url: `${config.corsOrigins[0]}/settings`,
    });

    res.json({ url: portalSession.url });
  } catch (error) {
    console.error('Create portal session error:', error);
    res.status(500).json({ error: 'Failed to create billing portal session' });
  }
});

// POST /subscription/cancel - Cancel subscription
router.post('/subscription/cancel', async (req: Request, res: Response) => {
  if (!requireStripe(res)) return;

  try {
    const userId = req.userId!;

    const subscription = await queryOne(
      `SELECT stripe_subscription_id, stripe_customer_id, plan
       FROM user_subscriptions
       WHERE user_id = $1`,
      [userId]
    );

    if (!subscription?.stripe_subscription_id) {
      res.status(404).json({ error: 'No active subscription found' });
      return;
    }

    // Cancel at end of billing period (not immediately)
    await stripe!.subscriptions.update(subscription.stripe_subscription_id, {
      cancel_at_period_end: true,
    });

    // Update database status to reflect pending cancellation
    await query(
      `UPDATE user_subscriptions
       SET status = 'cancelling'
       WHERE user_id = $1`,
      [userId]
    );

    res.json({
      success: true,
      message: 'Subscription will be cancelled at the end of the current billing period',
    });
  } catch (error) {
    console.error('Cancel subscription error:', error);
    res.status(500).json({ error: 'Failed to cancel subscription' });
  }
});

export default router;
