# Quick Deployment Guide

Deploy BM Backend to **217.154.181.175** in 3 steps.

## Prerequisites

- SSH access: `ssh root@217.154.181.175`
- Server already has Docling API and N8N running

## Option 1: Automated Deployment (Easiest)

```bash
# Make script executable
chmod +x deploy-remote.sh

# Run deployment
./deploy-remote.sh
```

That's it! The script handles everything.

## Option 2: Manual Quick Deploy

```bash
# 1. Build JAR
./gradlew clean shadowJar

# 2. Copy to server
scp build/libs/bm-backend-1.0-all.jar root@217.154.181.175:/root/BmBackEnd/
scp price_tables.db root@217.154.181.175:/root/BmBackEnd/
scp bm-backend.service root@217.154.181.175:/etc/systemd/system/

# 3. Start service
ssh root@217.154.181.175 "systemctl daemon-reload && \
    systemctl enable bm-backend && \
    systemctl start bm-backend"

# 4. Configure nginx
scp nginx-bm-backend.conf root@217.154.181.175:/etc/nginx/sites-available/bm-backend
ssh root@217.154.181.175 "ln -sf /etc/nginx/sites-available/bm-backend /etc/nginx/sites-enabled/ && \
    nginx -t && systemctl reload nginx"
```

## Test Deployment

```bash
# Test health endpoint
curl http://217.154.181.175:8081/health

# Test API
curl http://217.154.181.175:8081/api/v1/price-tables
```

## View Logs

```bash
ssh root@217.154.181.175 "journalctl -u bm-backend -f"
```

## Restart Service

```bash
ssh root@217.154.181.175 "systemctl restart bm-backend"
```

## Access Points

- **Direct API**: http://217.154.181.175:8081
- **Via nginx**: http://217.154.181.175/api/v1/
- **Health check**: http://217.154.181.175/backend/health

For detailed instructions, see [DEPLOYMENT.md](DEPLOYMENT.md)
