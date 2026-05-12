# Quick Deployment Guide

Deploy BM Backend + PostgreSQL to **217.154.181.175**.

## Prerequisites

- SSH access: `ssh root@217.154.181.175`
- Server already has Docling API and N8N running
- Environment variables ready: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `ENCRYPTION_KEY`

## Option 1: Docker Compose (Recommended)

```bash
# 1. Copy project to server
rsync -avz --exclude='.git' --exclude='build' --exclude='.gradle' \
    ./ root@217.154.181.175:/root/BmBackEnd/

# 2. Set env vars on server
ssh root@217.154.181.175 "cat > /root/BmBackEnd/.env << 'EOF'
DB_URL=jdbc:postgresql://postgres:5432/bm_backend
DB_USER=bm_user
DB_PASSWORD=your_secure_password
ENCRYPTION_KEY=your_32_byte_hex_key
EOF"

# 3. Start everything (PostgreSQL + backend)
ssh root@217.154.181.175 "cd /root/BmBackEnd && docker-compose up -d"
```

Flyway migrations run automatically on startup.

## Option 2: Manual Quick Deploy

```bash
# 1. Build JAR
./gradlew clean shadowJar

# 2. Copy to server
scp build/libs/bm-backend-1.0-all.jar root@217.154.181.175:/root/BmBackEnd/
scp bm-backend.service root@217.154.181.175:/etc/systemd/system/

# 3. Ensure PostgreSQL is running on server (via Docker or system package)

# 4. Start service (env vars must be set in bm-backend.service)
ssh root@217.154.181.175 "systemctl daemon-reload && \
    systemctl enable bm-backend && \
    systemctl start bm-backend"

# 5. Configure nginx
scp nginx-bm-backend.conf root@217.154.181.175:/etc/nginx/sites-available/bm-backend
ssh root@217.154.181.175 "ln -sf /etc/nginx/sites-available/bm-backend /etc/nginx/sites-enabled/ && \
    nginx -t && systemctl reload nginx"
```

## Migrate from SQLite (One-Time)

```bash
java -cp bm-backend-1.0-all.jar com.bm.backend.tools.SqliteToPostgresMigrationKt \
  --sqlite-path /path/to/price_tables.db \
  --pg-url jdbc:postgresql://localhost:5432/bm_backend \
  --pg-user bm_user \
  --pg-password secret
```

## Test Deployment

```bash
# Health check (includes DB connectivity)
curl http://217.154.181.175:8081/health

# Test API
curl http://217.154.181.175:8081/api/v1/price-tables
```

## View Logs

```bash
# Systemd
ssh root@217.154.181.175 "journalctl -u bm-backend -f"

# Docker
ssh root@217.154.181.175 "docker logs bm-backend -f"
```

## Database Backup

```bash
ssh root@217.154.181.175 "docker exec bm-postgres pg_dump -U bm_user bm_backend | gzip > /root/backups/bm_backend_$(date +%Y%m%d).sql.gz"
```

## Access Points

- **Direct API**: http://217.154.181.175:8081
- **Via nginx**: http://217.154.181.175/api/v1/
- **Health check**: http://217.154.181.175/backend/health

For detailed instructions, see [DEPLOYMENT.md](DEPLOYMENT.md)
