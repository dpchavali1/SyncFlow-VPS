import { Router, Request, Response } from 'express';
import crypto from 'crypto';
import { query } from '../services/database';
import { authenticate, requireAdmin } from '../middleware/auth';
import { rateLimit } from '../middleware/rateLimit';

const router = Router();

// ==================== Bot Detection ====================

const BOT_PATTERNS = [
  /bot/i, /crawl/i, /spider/i, /slurp/i, /mediapartners/i,
  /facebookexternalhit/i, /linkedinbot/i, /twitterbot/i,
  /whatsapp/i, /telegrambot/i, /discordbot/i, /pingdom/i,
  /uptimerobot/i, /googlebot/i, /bingbot/i, /yandexbot/i,
  /baiduspider/i, /duckduckbot/i, /ia_archiver/i, /semrushbot/i,
  /ahrefsbot/i, /mj12bot/i, /dotbot/i, /petalbot/i,
  /headlesschrome/i, /phantomjs/i, /python-requests/i,
  /curl/i, /wget/i, /httpie/i, /postman/i,
];

function isBot(ua: string): boolean {
  return BOT_PATTERNS.some(p => p.test(ua));
}

// ==================== UA Parsing ====================

function parseUserAgent(ua: string): { deviceType: string; osName: string; browserName: string } {
  let deviceType = 'desktop';
  if (/mobile|android.*mobile|iphone|ipod/i.test(ua)) deviceType = 'mobile';
  else if (/tablet|ipad|android(?!.*mobile)/i.test(ua)) deviceType = 'tablet';

  let osName = 'Unknown';
  if (/windows/i.test(ua)) osName = 'Windows';
  else if (/macintosh|mac os/i.test(ua)) osName = 'macOS';
  else if (/linux/i.test(ua)) osName = 'Linux';
  else if (/android/i.test(ua)) osName = 'Android';
  else if (/iphone|ipad|ipod/i.test(ua)) osName = 'iOS';
  else if (/chromeos/i.test(ua)) osName = 'ChromeOS';

  let browserName = 'Unknown';
  if (/edg\//i.test(ua)) browserName = 'Edge';
  else if (/opr\//i.test(ua) || /opera/i.test(ua)) browserName = 'Opera';
  else if (/firefox/i.test(ua)) browserName = 'Firefox';
  else if (/chrome/i.test(ua) && !/edg/i.test(ua)) browserName = 'Chrome';
  else if (/safari/i.test(ua) && !/chrome/i.test(ua)) browserName = 'Safari';
  else if (/msie|trident/i.test(ua)) browserName = 'IE';

  return { deviceType, osName, browserName };
}

// ==================== Country Name Mapping ====================

const COUNTRY_NAMES: Record<string, string> = {
  US: 'United States', GB: 'United Kingdom', CA: 'Canada', AU: 'Australia',
  DE: 'Germany', FR: 'France', JP: 'Japan', IN: 'India', BR: 'Brazil',
  MX: 'Mexico', IT: 'Italy', ES: 'Spain', KR: 'South Korea', NL: 'Netherlands',
  SE: 'Sweden', NO: 'Norway', DK: 'Denmark', FI: 'Finland', CH: 'Switzerland',
  AT: 'Austria', BE: 'Belgium', PT: 'Portugal', PL: 'Poland', CZ: 'Czech Republic',
  RO: 'Romania', HU: 'Hungary', IE: 'Ireland', NZ: 'New Zealand', SG: 'Singapore',
  HK: 'Hong Kong', TW: 'Taiwan', PH: 'Philippines', TH: 'Thailand', ID: 'Indonesia',
  MY: 'Malaysia', VN: 'Vietnam', RU: 'Russia', UA: 'Ukraine', TR: 'Turkey',
  ZA: 'South Africa', NG: 'Nigeria', EG: 'Egypt', KE: 'Kenya', AR: 'Argentina',
  CL: 'Chile', CO: 'Colombia', PE: 'Peru', IL: 'Israel', AE: 'United Arab Emirates',
  SA: 'Saudi Arabia', PK: 'Pakistan', BD: 'Bangladesh', CN: 'China',
};

// ==================== Rate Limiter ====================

const trackRateLimit = rateLimit({
  windowMs: 60000,
  maxRequests: 30,
  keyPrefix: 'rl:analytics',
});

// ==================== Public Endpoints ====================

// POST /track - Record an analytics event (public, no auth)
router.post('/track', trackRateLimit, async (req: Request, res: Response) => {
  try {
    const ua = req.headers['user-agent'] || '';

    // Skip bots
    if (isBot(ua)) {
      res.status(204).end();
      return;
    }

    const { eventType, pagePath, referrer, downloadPlatform, utmSource, utmMedium, utmCampaign } = req.body;

    if (!eventType || !['page_view', 'download_click', 'button_click'].includes(eventType)) {
      res.status(400).json({ error: 'Invalid event type' });
      return;
    }

    // Generate anonymous session ID: SHA-256(IP + UA)
    const ip = req.headers['x-forwarded-for']?.toString().split(',')[0]?.trim() || req.ip || 'unknown';
    const sessionId = crypto.createHash('sha256').update(`${ip}|${ua}`).digest('hex');

    // Parse UA
    const { deviceType, osName, browserName } = parseUserAgent(ua);

    // Country from Cloudflare header
    const countryCode = (req.headers['cf-ipcountry'] as string) || null;
    const countryName = countryCode ? (COUNTRY_NAMES[countryCode] || countryCode) : null;

    await query(
      `INSERT INTO analytics_events (session_id, event_type, page_path, referrer, download_platform, device_type, os_name, browser_name, country_code, country_name, utm_source, utm_medium, utm_campaign)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)`,
      [
        sessionId,
        eventType,
        pagePath ? String(pagePath).slice(0, 500) : null,
        referrer ? String(referrer).slice(0, 1000) : null,
        downloadPlatform ? String(downloadPlatform).slice(0, 20) : null,
        deviceType,
        osName,
        browserName,
        countryCode ? String(countryCode).slice(0, 2) : null,
        countryName ? String(countryName).slice(0, 100) : null,
        utmSource ? String(utmSource).slice(0, 255) : null,
        utmMedium ? String(utmMedium).slice(0, 255) : null,
        utmCampaign ? String(utmCampaign).slice(0, 255) : null,
      ]
    );

    res.status(204).end();
  } catch (error) {
    console.error('[Analytics] Track error:', error);
    // Still return 204 to not break client
    res.status(204).end();
  }
});

// ==================== Admin Endpoints ====================

// GET /admin/overview?days=30 - Full analytics overview
router.get('/admin/overview', authenticate, requireAdmin, async (req: Request, res: Response) => {
  try {
    const days = Math.min(Math.max(parseInt(req.query.days as string) || 30, 1), 365);
    const since = `NOW() - INTERVAL '${days} days'`;

    // Totals
    const [totals] = await query<{ page_views: string; unique_visitors: string; downloads: string }>(
      `SELECT
        COUNT(*) FILTER (WHERE event_type = 'page_view') AS page_views,
        COUNT(DISTINCT session_id) FILTER (WHERE event_type = 'page_view') AS unique_visitors,
        COUNT(*) FILTER (WHERE event_type = 'download_click') AS downloads
       FROM analytics_events WHERE created_at >= ${since}`
    );

    // Daily trend
    const dailyTrend = await query<{ date: string; page_views: string; unique_visitors: string }>(
      `SELECT created_at::date AS date,
        COUNT(*) FILTER (WHERE event_type = 'page_view') AS page_views,
        COUNT(DISTINCT session_id) FILTER (WHERE event_type = 'page_view') AS unique_visitors
       FROM analytics_events WHERE created_at >= ${since}
       GROUP BY created_at::date ORDER BY date`
    );

    // Top pages
    const topPages = await query<{ page_path: string; views: string }>(
      `SELECT page_path, COUNT(*) AS views
       FROM analytics_events WHERE event_type = 'page_view' AND created_at >= ${since} AND page_path IS NOT NULL
       GROUP BY page_path ORDER BY views DESC LIMIT 20`
    );

    // Top referrers
    const topReferrers = await query<{ referrer: string; count: string }>(
      `SELECT referrer, COUNT(*) AS count
       FROM analytics_events WHERE event_type = 'page_view' AND created_at >= ${since} AND referrer IS NOT NULL AND referrer != ''
       GROUP BY referrer ORDER BY count DESC LIMIT 20`
    );

    // Device type breakdown
    const deviceBreakdown = await query<{ device_type: string; count: string }>(
      `SELECT device_type, COUNT(*) AS count
       FROM analytics_events WHERE created_at >= ${since}
       GROUP BY device_type ORDER BY count DESC`
    );

    // OS breakdown
    const osBreakdown = await query<{ os_name: string; count: string }>(
      `SELECT os_name, COUNT(*) AS count
       FROM analytics_events WHERE created_at >= ${since}
       GROUP BY os_name ORDER BY count DESC`
    );

    // Browser breakdown
    const browserBreakdown = await query<{ browser_name: string; count: string }>(
      `SELECT browser_name, COUNT(*) AS count
       FROM analytics_events WHERE created_at >= ${since}
       GROUP BY browser_name ORDER BY count DESC`
    );

    // Country breakdown
    const countryBreakdown = await query<{ country_code: string; country_name: string; count: string }>(
      `SELECT country_code, country_name, COUNT(*) AS count
       FROM analytics_events WHERE created_at >= ${since} AND country_code IS NOT NULL
       GROUP BY country_code, country_name ORDER BY count DESC LIMIT 20`
    );

    // Downloads by platform
    const downloadsByPlatform = await query<{ download_platform: string; count: string }>(
      `SELECT download_platform, COUNT(*) AS count
       FROM analytics_events WHERE event_type = 'download_click' AND created_at >= ${since} AND download_platform IS NOT NULL
       GROUP BY download_platform ORDER BY count DESC`
    );

    res.json({
      days,
      totals: {
        pageViews: parseInt(totals?.page_views || '0'),
        uniqueVisitors: parseInt(totals?.unique_visitors || '0'),
        downloads: parseInt(totals?.downloads || '0'),
      },
      dailyTrend: dailyTrend.map(d => ({
        date: d.date,
        pageViews: parseInt(d.page_views),
        uniqueVisitors: parseInt(d.unique_visitors),
      })),
      topPages: topPages.map(p => ({ path: p.page_path, views: parseInt(p.views) })),
      topReferrers: topReferrers.map(r => ({ referrer: r.referrer, count: parseInt(r.count) })),
      deviceBreakdown: deviceBreakdown.map(d => ({ type: d.device_type, count: parseInt(d.count) })),
      osBreakdown: osBreakdown.map(o => ({ name: o.os_name, count: parseInt(o.count) })),
      browserBreakdown: browserBreakdown.map(b => ({ name: b.browser_name, count: parseInt(b.count) })),
      countryBreakdown: countryBreakdown.map(c => ({ code: c.country_code, name: c.country_name, count: parseInt(c.count) })),
      downloadsByPlatform: downloadsByPlatform.map(d => ({ platform: d.download_platform, count: parseInt(d.count) })),
    });
  } catch (error) {
    console.error('[Analytics] Admin overview error:', error);
    res.status(500).json({ error: 'Failed to load analytics overview' });
  }
});

// GET /admin/visits?days=30&granularity=daily - Visit trends
router.get('/admin/visits', authenticate, requireAdmin, async (req: Request, res: Response) => {
  try {
    const days = Math.min(Math.max(parseInt(req.query.days as string) || 30, 1), 365);
    const since = `NOW() - INTERVAL '${days} days'`;

    const visits = await query<{ date: string; page_views: string; unique_visitors: string; downloads: string }>(
      `SELECT created_at::date AS date,
        COUNT(*) FILTER (WHERE event_type = 'page_view') AS page_views,
        COUNT(DISTINCT session_id) FILTER (WHERE event_type = 'page_view') AS unique_visitors,
        COUNT(*) FILTER (WHERE event_type = 'download_click') AS downloads
       FROM analytics_events WHERE created_at >= ${since}
       GROUP BY created_at::date ORDER BY date`
    );

    res.json({
      days,
      visits: visits.map(v => ({
        date: v.date,
        pageViews: parseInt(v.page_views),
        uniqueVisitors: parseInt(v.unique_visitors),
        downloads: parseInt(v.downloads),
      })),
    });
  } catch (error) {
    console.error('[Analytics] Visits error:', error);
    res.status(500).json({ error: 'Failed to load visit data' });
  }
});

// GET /admin/downloads?days=30 - Download metrics
router.get('/admin/downloads', authenticate, requireAdmin, async (req: Request, res: Response) => {
  try {
    const days = Math.min(Math.max(parseInt(req.query.days as string) || 30, 1), 365);
    const since = `NOW() - INTERVAL '${days} days'`;

    const byPlatform = await query<{ download_platform: string; count: string }>(
      `SELECT download_platform, COUNT(*) AS count
       FROM analytics_events WHERE event_type = 'download_click' AND created_at >= ${since} AND download_platform IS NOT NULL
       GROUP BY download_platform ORDER BY count DESC`
    );

    const byDay = await query<{ date: string; download_platform: string; count: string }>(
      `SELECT created_at::date AS date, download_platform, COUNT(*) AS count
       FROM analytics_events WHERE event_type = 'download_click' AND created_at >= ${since} AND download_platform IS NOT NULL
       GROUP BY created_at::date, download_platform ORDER BY date`
    );

    res.json({
      days,
      byPlatform: byPlatform.map(p => ({ platform: p.download_platform, count: parseInt(p.count) })),
      byDay: byDay.map(d => ({ date: d.date, platform: d.download_platform, count: parseInt(d.count) })),
    });
  } catch (error) {
    console.error('[Analytics] Downloads error:', error);
    res.status(500).json({ error: 'Failed to load download data' });
  }
});

export default router;
