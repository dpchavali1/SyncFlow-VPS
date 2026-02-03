# Hetzner VPS Setup Instructions

## Step 1: Create Hetzner Account

1. Go to https://www.hetzner.com/cloud
2. Click "Sign Up" and create an account
3. Verify your email and complete registration
4. Add a payment method (credit card or PayPal)

---

## Step 2: Create a New Project

1. Log in to https://console.hetzner.cloud/
2. Click "New Project"
3. Name it: `SyncFlow`
4. Click "Add Project"

---

## Step 3: Add SSH Key (Important - Do This First!)

1. In your project, go to **Security** → **SSH Keys**
2. Click **Add SSH Key**
3. On your Mac, run this to get your public key:
   ```bash
   cat ~/.ssh/id_rsa.pub
   ```
   If you don't have one, generate it first:
   ```bash
   ssh-keygen -t rsa -b 4096 -C "your-email@example.com"
   cat ~/.ssh/id_rsa.pub
   ```
4. Copy the entire output and paste into Hetzner
5. Name it (e.g., "MacBook Pro")
6. Click **Add SSH Key**

---

## Step 4: Create the VPS Server

1. Go to **Servers** → **Add Server**

2. **Location**: Choose closest to your users
   - Recommended: `Ashburn, VA` (US East) or `Hillsboro, OR` (US West)
   - For EU: `Falkenstein` or `Helsinki`

3. **Image**: Select **Ubuntu 22.04**

4. **Type**: Select **CX32** (recommended)
   - 4 vCPU (shared)
   - 8 GB RAM
   - 80 GB NVMe SSD
   - 20 TB traffic
   - ~€7.59/month (~$8.50/month)

   *Alternative: CX42 for more headroom*
   - 8 vCPU, 16 GB RAM, 160 GB SSD
   - ~€14.49/month (~$16/month)

5. **Networking**:
   - [x] Public IPv4 (required)
   - [x] Public IPv6 (recommended)

6. **SSH Keys**: Select your SSH key from Step 3

7. **Volumes**: Skip (not needed initially)

8. **Firewalls**: Skip for now (we'll configure UFW on server)

9. **Backups**:
   - [x] Enable backups (+20% cost, ~$2/month)
   - Recommended for production

10. **Placement Groups**: Skip

11. **Labels**: Add `env:production`

12. **Cloud Config**: Leave empty

13. **Name**: `syncflow-prod-1`

14. Click **Create & Buy Now**

---

## Step 5: Note Your Server Details

After creation, note:
- **IPv4 Address**: `xxx.xxx.xxx.xxx` (write this down)
- **IPv6 Address**: (optional)

---

## Step 6: Connect to Your Server

```bash
ssh root@YOUR_SERVER_IP
```

You should connect without a password (using your SSH key).

---

## Step 7: Initial Server Setup

Run these commands after connecting:

### Update System
```bash
apt update && apt upgrade -y
```

### Create Non-Root User
```bash
adduser syncflow
usermod -aG sudo syncflow

# Copy SSH key to new user
mkdir -p /home/syncflow/.ssh
cp ~/.ssh/authorized_keys /home/syncflow/.ssh/
chown -R syncflow:syncflow /home/syncflow/.ssh
chmod 700 /home/syncflow/.ssh
chmod 600 /home/syncflow/.ssh/authorized_keys
```

### Disable Root SSH & Password Auth
```bash
nano /etc/ssh/sshd_config
```
Change these lines:
```
PermitRootLogin no
PasswordAuthentication no
```
Save and restart SSH:
```bash
systemctl restart sshd
```

### Configure Firewall (UFW)
```bash
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
```

### Set Timezone
```bash
timedatectl set-timezone UTC
```

### Enable Automatic Security Updates
```bash
apt install unattended-upgrades -y
dpkg-reconfigure -plow unattended-upgrades
```

---

## Step 8: Install PostgreSQL 16

```bash
# Add PostgreSQL repository
sh -c 'echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list'
wget -qO- https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo tee /etc/apt/trusted.gpg.d/pgdg.asc &>/dev/null

apt update
apt install postgresql-16 postgresql-contrib-16 -y

# Start and enable
systemctl start postgresql
systemctl enable postgresql

# Create database and user
sudo -u postgres psql << EOF
CREATE USER syncflow WITH PASSWORD 'GENERATE_STRONG_PASSWORD_HERE';
CREATE DATABASE syncflow_prod OWNER syncflow;
GRANT ALL PRIVILEGES ON DATABASE syncflow_prod TO syncflow;
\c syncflow_prod
GRANT ALL ON SCHEMA public TO syncflow;
EOF
```

**Important**: Generate a strong password:
```bash
openssl rand -base64 32
```

---

## Step 9: Install Redis

```bash
apt install redis-server -y

# Configure Redis
nano /etc/redis/redis.conf
```
Change these settings:
```
supervised systemd
bind 127.0.0.1 ::1
requirepass GENERATE_STRONG_PASSWORD_HERE
maxmemory 512mb
maxmemory-policy allkeys-lru
```

```bash
systemctl restart redis
systemctl enable redis
```

---

## Step 10: Install Node.js 20 LTS

```bash
# Switch to syncflow user
su - syncflow

# Install nvm
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
source ~/.bashrc

# Install Node.js 20
nvm install 20
nvm use 20
nvm alias default 20

# Verify
node --version  # Should show v20.x.x
npm --version

# Install PM2 globally
npm install -g pm2

# Configure PM2 startup
pm2 startup
# Run the command it outputs (as root)
```

---

## Step 11: Install Nginx

```bash
# As root
apt install nginx -y
systemctl enable nginx
```

---

## Step 12: Install Certbot (SSL)

```bash
apt install certbot python3-certbot-nginx -y
```

---

## Step 13: Configure Domain (Do This in Your DNS Provider)

Point your domain to the server:
```
A    api.syncflow.app    YOUR_SERVER_IP
AAAA api.syncflow.app    YOUR_SERVER_IPV6 (optional)
```

Wait for DNS propagation (5-30 minutes).

---

## Step 14: Generate SSL Certificate

```bash
certbot --nginx -d api.syncflow.app
```

Follow the prompts:
- Enter email for renewal notices
- Agree to terms
- Choose to redirect HTTP to HTTPS (recommended)

Test auto-renewal:
```bash
certbot renew --dry-run
```

---

## Step 15: Configure Nginx as Reverse Proxy

```bash
nano /etc/nginx/sites-available/syncflow
```

```nginx
upstream syncflow_api {
    server 127.0.0.1:3000;
    keepalive 64;
}

upstream syncflow_ws {
    server 127.0.0.1:3001;
    keepalive 64;
}

server {
    listen 80;
    server_name api.syncflow.app;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name api.syncflow.app;

    ssl_certificate /etc/letsencrypt/live/api.syncflow.app/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.syncflow.app/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256;
    ssl_prefer_server_ciphers off;

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;

    # REST API
    location /api {
        proxy_pass http://syncflow_api;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Timeouts
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    # WebSocket
    location /ws {
        proxy_pass http://syncflow_ws;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket timeouts
        proxy_connect_timeout 7d;
        proxy_send_timeout 7d;
        proxy_read_timeout 7d;
    }

    # Health check
    location /health {
        proxy_pass http://syncflow_api/health;
        proxy_http_version 1.1;
    }
}
```

Enable the site:
```bash
ln -s /etc/nginx/sites-available/syncflow /etc/nginx/sites-enabled/
nginx -t
systemctl reload nginx
```

---

## Step 16: Install Fail2ban (Security)

```bash
apt install fail2ban -y

nano /etc/fail2ban/jail.local
```

```ini
[DEFAULT]
bantime = 1h
findtime = 10m
maxretry = 5

[sshd]
enabled = true
port = ssh
filter = sshd
logpath = /var/log/auth.log
maxretry = 3
bantime = 24h
```

```bash
systemctl enable fail2ban
systemctl start fail2ban
```

---

## Step 17: Set Up Daily Backups

Create backup script:
```bash
mkdir -p /home/syncflow/backups
nano /home/syncflow/backup.sh
```

```bash
#!/bin/bash
BACKUP_DIR="/home/syncflow/backups"
DATE=$(date +%Y%m%d_%H%M%S)
KEEP_DAYS=7

# PostgreSQL backup
PGPASSWORD="YOUR_PG_PASSWORD" pg_dump -U syncflow -h localhost syncflow_prod | gzip > "$BACKUP_DIR/db_$DATE.sql.gz"

# Remove old backups
find $BACKUP_DIR -name "db_*.sql.gz" -mtime +$KEEP_DAYS -delete

echo "Backup completed: db_$DATE.sql.gz"
```

```bash
chmod +x /home/syncflow/backup.sh
```

Add to crontab:
```bash
crontab -e
```
Add:
```
0 3 * * * /home/syncflow/backup.sh >> /home/syncflow/backups/backup.log 2>&1
```

---

## Step 18: Verify Installation

```bash
# Check PostgreSQL
sudo -u postgres psql -c "SELECT version();"

# Check Redis
redis-cli -a YOUR_REDIS_PASSWORD ping

# Check Node.js (as syncflow user)
su - syncflow
node --version
pm2 --version

# Check Nginx
nginx -t
curl -I https://api.syncflow.app/health  # Should return 502 until app is deployed
```

---

## Summary: What You Now Have

| Component | Version | Port | Status |
|-----------|---------|------|--------|
| Ubuntu | 22.04 LTS | - | Running |
| PostgreSQL | 16 | 5432 (local) | Running |
| Redis | 7.x | 6379 (local) | Running |
| Node.js | 20 LTS | - | Installed |
| PM2 | Latest | - | Installed |
| Nginx | Latest | 80, 443 | Running |
| SSL | Let's Encrypt | - | Configured |
| Firewall | UFW | 22, 80, 443 | Active |
| Fail2ban | Latest | - | Active |

---

## Important Credentials to Save Securely

Store these somewhere safe (password manager):

1. **Server IP**: `xxx.xxx.xxx.xxx`
2. **SSH User**: `syncflow`
3. **PostgreSQL User**: `syncflow`
4. **PostgreSQL Password**: `[generated]`
5. **PostgreSQL Database**: `syncflow_prod`
6. **Redis Password**: `[generated]`

---

## Next Steps

1. Let me know once VPS is created and configured
2. I'll help you deploy the server application
3. Then we'll proceed with client updates and data migration

---

## Troubleshooting

### Can't connect via SSH
```bash
# Check if server is running in Hetzner console
# Check if your IP is blocked by fail2ban
# Try connecting from different network
```

### PostgreSQL connection refused
```bash
systemctl status postgresql
sudo -u postgres psql
```

### Nginx not starting
```bash
nginx -t  # Check for config errors
systemctl status nginx
journalctl -u nginx
```

### SSL certificate issues
```bash
certbot certificates  # List certificates
certbot renew --force-renewal  # Force renewal
```
