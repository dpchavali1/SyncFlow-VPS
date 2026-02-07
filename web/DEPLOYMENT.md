# SyncFlow Web - Deployment Guide

This guide will help you deploy SyncFlow Web to production.

## Quick Deploy to Vercel (5 minutes)

Vercel is the easiest and recommended platform for deploying Next.js applications.

### Prerequisites
- GitHub account
- Vercel account (free at [vercel.com](https://vercel.com))
- Firebase project configured

### Step 1: Push to GitHub

```bash
cd /Users/dchavali/Documents/GitHub/SyncFlow
git add web/
git commit -m "Add SyncFlow Web application"
git push origin main
```

### Step 2: Deploy to Vercel

#### Option A: Via Vercel Dashboard (Easiest)

1. Go to [vercel.com](https://vercel.com)
2. Click **"Add New"** → **"Project"**
3. Import your GitHub repository: `SyncFlow`
4. Configure project:
   - **Framework Preset**: Next.js
   - **Root Directory**: `web`
   - **Build Command**: `npm run build`
   - **Output Directory**: `.next`
5. Click **"Deploy"**

#### Option B: Via Vercel CLI

```bash
# Install Vercel CLI
npm install -g vercel

# Login to Vercel
vercel login

# Deploy from web directory
cd web
vercel

# Follow prompts:
# - Link to existing project? No
# - What's your project name? syncflow-web
# - In which directory is your code? ./
# - Want to override settings? No
```

### Step 3: Add Environment Variables

1. Go to your Vercel project dashboard
2. Click **Settings** → **Environment Variables**
3. Add the following variables:

```
NEXT_PUBLIC_FIREBASE_API_KEY=your_api_key
NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=your_project.firebaseapp.com
NEXT_PUBLIC_FIREBASE_DATABASE_URL=https://your_project-default-rtdb.firebaseio.com
NEXT_PUBLIC_FIREBASE_PROJECT_ID=your_project_id
NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET=your_project.appspot.com
NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID=your_sender_id
NEXT_PUBLIC_FIREBASE_APP_ID=your_app_id
```

4. Click **"Save"**
5. Redeploy: **Deployments** → **...** → **Redeploy**

### Step 4: Test Your Deployment

1. Visit your Vercel URL: `https://your-project.vercel.app`
2. Open SyncFlow Android app
3. Go to Settings → Desktop Integration
4. Pair with your deployed web app
5. Test sending and receiving messages

## Custom Domain Setup

### Add Domain to Vercel

1. Go to **Settings** → **Domains**
2. Add your domain: `syncflow.app` or `app.syncflow.com`
3. Configure DNS:

**For root domain (syncflow.app):**
```
Type: A
Name: @
Value: 76.76.21.21
```

**For subdomain (app.syncflow.com):**
```
Type: CNAME
Name: app
Value: cname.vercel-dns.com
```

4. Wait for DNS propagation (5-30 minutes)
5. Vercel will automatically provision SSL certificate

## Deploy to Netlify

### Step 1: Build Configuration

Create `netlify.toml` in the `web` directory:

```toml
[build]
  base = "web"
  command = "npm run build"
  publish = ".next"

[[plugins]]
  package = "@netlify/plugin-nextjs"
```

### Step 2: Deploy

#### Via Netlify Dashboard

1. Go to [netlify.com](https://netlify.com)
2. Click **"Add new site"** → **"Import an existing project"**
3. Connect to GitHub and select SyncFlow repository
4. Configure:
   - **Base directory**: `web`
   - **Build command**: `npm run build`
   - **Publish directory**: `.next`
5. Add environment variables (same as Vercel)
6. Click **"Deploy site"**

#### Via Netlify CLI

```bash
# Install Netlify CLI
npm install -g netlify-cli

# Login
netlify login

# Deploy
cd web
netlify deploy --prod
```

## Deploy to Your Own Server

### Requirements
- Ubuntu/Debian server with SSH access
- Node.js 18+ installed
- Nginx installed
- Domain pointing to your server

### Step 1: Build the Application

```bash
cd web
npm install
npm run build
```

### Step 2: Set Up Server

```bash
# SSH into your server
ssh user@your-server.com

# Install Node.js (if not installed)
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt-get install -y nodejs

# Install PM2 for process management
sudo npm install -g pm2

# Create app directory
sudo mkdir -p /var/www/syncflow
sudo chown -R $USER:$USER /var/www/syncflow
```

### Step 3: Upload Files

```bash
# From your local machine
cd web
rsync -avz --exclude 'node_modules' . user@your-server.com:/var/www/syncflow/
```

### Step 4: Install Dependencies and Start

```bash
# On your server
cd /var/www/syncflow
npm install
npm run build

# Create .env.local with your Firebase config

# Start with PM2
pm2 start npm --name "syncflow-web" -- start
pm2 save
pm2 startup
```

### Step 5: Configure Nginx

```bash
# Create Nginx configuration
sudo nano /etc/nginx/sites-available/syncflow
```

Add this configuration:

```nginx
server {
    listen 80;
    server_name syncflow.app www.syncflow.app;

    location / {
        proxy_pass http://localhost:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
    }
}
```

Enable the site:

```bash
sudo ln -s /etc/nginx/sites-available/syncflow /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

### Step 6: Add SSL with Let's Encrypt

```bash
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d syncflow.app -d www.syncflow.app
```

## Continuous Deployment

### GitHub Actions (Vercel)

Create `.github/workflows/deploy.yml`:

```yaml
name: Deploy to Vercel

on:
  push:
    branches: [main]
    paths:
      - 'web/**'

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: amondnet/vercel-action@v20
        with:
          vercel-token: ${{ secrets.VERCEL_TOKEN }}
          vercel-org-id: ${{ secrets.ORG_ID }}
          vercel-project-id: ${{ secrets.PROJECT_ID }}
          working-directory: ./web
```

### GitHub Actions (Custom Server)

```yaml
name: Deploy to Server

on:
  push:
    branches: [main]
    paths:
      - 'web/**'

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Deploy to server
        uses: appleboy/ssh-action@master
        with:
          host: ${{ secrets.SERVER_HOST }}
          username: ${{ secrets.SERVER_USER }}
          key: ${{ secrets.SSH_PRIVATE_KEY }}
          script: |
            cd /var/www/syncflow
            git pull
            npm install
            npm run build
            pm2 restart syncflow-web
```

## Performance Optimization

### 1. Enable Caching

Vercel and Netlify automatically cache static assets. For custom servers:

```nginx
# Add to Nginx config
location /_next/static {
    alias /var/www/syncflow/.next/static;
    expires 365d;
    access_log off;
}
```

### 2. Enable Compression

```nginx
# Add to Nginx config
gzip on;
gzip_types text/plain text/css application/json application/javascript;
gzip_min_length 1000;
```

### 3. Use CDN

Consider using Cloudflare for:
- Global CDN
- DDoS protection
- Free SSL
- Analytics

## Monitoring

### Vercel Analytics

```bash
# Install Vercel Analytics
npm install @vercel/analytics

# Add to app/layout.tsx
import { Analytics } from '@vercel/analytics/react'

export default function RootLayout({ children }) {
  return (
    <html>
      <body>
        {children}
        <Analytics />
      </body>
    </html>
  )
}
```

### Error Tracking with Sentry

```bash
npm install @sentry/nextjs

# Follow Sentry setup wizard
npx @sentry/wizard@latest -i nextjs
```

## Cost Estimates

### Vercel
- **Hobby (Free)**: Good for personal use
  - 100GB bandwidth/month
  - Unlimited websites
  - Automatic HTTPS
  - **Cost**: $0/month

- **Pro**: For production
  - 1TB bandwidth/month
  - Team collaboration
  - Analytics
  - **Cost**: $20/month

### Netlify
- **Starter (Free)**: Good for personal use
  - 100GB bandwidth/month
  - 300 build minutes/month
  - **Cost**: $0/month

- **Pro**: For production
  - 1TB bandwidth/month
  - **Cost**: $19/month

### Custom Server (DigitalOcean/AWS)
- **DigitalOcean Droplet**: $6-12/month
- **AWS Lightsail**: $5-10/month
- **Domain**: $10-15/year
- **SSL**: Free (Let's Encrypt)

## Troubleshooting

### Build fails on Vercel/Netlify

- Check build logs for specific errors
- Ensure all dependencies are in `package.json`
- Verify environment variables are set
- Try building locally first: `npm run build`

### 502 Bad Gateway (Custom Server)

- Check if app is running: `pm2 status`
- Check logs: `pm2 logs syncflow-web`
- Restart app: `pm2 restart syncflow-web`
- Check Nginx: `sudo systemctl status nginx`

### SSL certificate errors

- Verify DNS is pointing to correct server
- Wait for DNS propagation (up to 48 hours)
- Run certbot again: `sudo certbot --nginx`

## Security Checklist

- [ ] All environment variables are set
- [ ] Firebase security rules are configured
- [ ] HTTPS is enabled
- [ ] CORS is configured properly
- [ ] Rate limiting is enabled (if needed)
- [ ] Security headers are set
- [ ] Regular backups are configured

## Next Steps

After deployment:
1. Test pairing with your phone
2. Test sending/receiving messages
3. Install PWA on desktop
4. Set up monitoring/analytics
5. Share with friends! 🎉

---

**Need help?** Check the main README.md or Firebase Console for troubleshooting.
