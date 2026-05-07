# BM Backend Deployment Guide

This guide covers deploying the BM Backend (Kotlin/Ktor + PostgreSQL) to your remote server at **217.154.181.175**.

## 🏗️ Architecture Overview

**BM Backend:**
- Kotlin/Ktor REST API
- Port: 8081
- PostgreSQL 16 database (via Docker or system-installed)
- HikariCP connection pooling, Flyway migrations
- Depends on:
  - Docling API (port 5000) - for PDF extraction
  - N8N webhook (port 5678) - for workflow processing

**Server Setup:**
- Server: 217.154.181.175
- Already has Docling API and N8N deployed
- Uses systemd services and nginx reverse proxy

## 📋 Prerequisites

### On Your Local Machine
- SSH access to the server: `ssh root@217.154.181.175`
- Gradle installed (or use `./gradlew`)
- Docker (for running tests with Testcontainers)

### On Remote Server
The server already has:
- ✅ Docker & Docker Compose
- ✅ Nginx
- ✅ Docling API (port 5000)
- ✅ N8N (port 5678)

Additional requirements:
- Java 17+ (for non-Docker deployment)
- PostgreSQL 16+ (via Docker Compose or system package)

## 🚀 Deployment Options

### Option 1: Automated Deployment (Recommended)

The `deploy-remote.sh` script handles everything automatically.

#### Step 1: Configure Deployment Script

Edit `deploy-remote.sh` and verify these settings:

```bash
REMOTE_SERVER="root@217.154.181.175"
REMOTE_PATH="/root/BmBackEnd"
USE_DOCKER=false  # Set to true for Docker deployment
GITHUB_REPO=""    # Optional: Set your GitHub repo URL
```

#### Step 2: Make Script Executable

```bash
chmod +x deploy-remote.sh
```

#### Step 3: Run Deployment

```bash
./deploy-remote.sh
```

The script will:
1. ✅ Check required files
2. ✅ Copy files to server (rsync or git)
3. ✅ Build JAR on server
4. ✅ Set up systemd service or Docker container
5. ✅ Configure nginx reverse proxy
6. ✅ Test deployment

### Option 2: Manual Deployment

#### Step 1: Build JAR Locally

```bash
./gradlew clean shadowJar
```

This creates: `build/libs/bm-backend-1.0-all.jar`

#### Step 2: Copy Files to Server

```bash
# Create directory on server
ssh root@217.154.181.175 "mkdir -p /root/BmBackEnd"

# Copy JAR file
scp build/libs/bm-backend-1.0-all.jar root@217.154.181.175:/root/BmBackEnd/

# Copy application config
scp -r src/main/resources root@217.154.181.175:/root/BmBackEnd/
```

> **Note:** The database is now PostgreSQL — no `.db` file to copy. Flyway runs migrations automatically on startup.

#### Step 3: Set Up Systemd Service

```bash
# Copy service file
scp bm-backend.service root@217.154.181.175:/etc/systemd/system/

# Enable and start service
ssh root@217.154.181.175 "systemctl daemon-reload && \
    systemctl enable bm-backend && \
    systemctl start bm-backend"
```

#### Step 4: Configure Nginx

```bash
# Copy nginx config
scp nginx-bm-backend.conf root@217.154.181.175:/etc/nginx/sites-available/bm-backend

# Enable site
ssh root@217.154.181.175 "ln -sf /etc/nginx/sites-available/bm-backend /etc/nginx/sites-enabled/ && \
    nginx -t && \
    systemctl reload nginx"
```

### Option 3: Docker Deployment

#### Step 1: Copy Files to Server

```bash
# Copy entire project
rsync -avz --exclude='.git' --exclude='build' --exclude='.gradle' \
    ./ root@217.154.181.175:/root/BmBackEnd/
```

#### Step 2: Build and Run with Docker Compose

```bash
ssh root@217.154.181.175 "cd /root/BmBackEnd && \
    docker-compose up -d --build"
```

## 🧪 Testing Deployment

### 1. Check Service Status

**Systemd:**
```bash
ssh root@217.154.181.175 "systemctl status bm-backend"
```

**Docker:**
```bash
ssh root@217.154.181.175 "docker ps | grep bm-backend"
```

### 2. Test Health Endpoint

```bash
# Direct access
curl http://217.154.181.175:8081/health

# Via nginx
curl http://217.154.181.175/backend/health
```

Expected response:
```json
{
  "status": "healthy",
  "timestamp": "2024-11-19T07:00:00Z"
}
```

### 3. Test API Endpoints

```bash
# List price tables
curl http://217.154.181.175:8081/api/v1/price-tables

# Via nginx
curl http://217.154.181.175/api/v1/price-tables
```

### 4. View Logs

**Systemd:**
```bash
ssh root@217.154.181.175 "journalctl -u bm-backend -f"
```

**Docker:**
```bash
ssh root@217.154.181.175 "docker logs bm-backend -f"
```

## 🔧 Configuration

### Application Configuration

Edit `src/main/resources/application.yaml`:

```yaml
ktor:
  deployment:
    port: 8081
    host: 0.0.0.0
  application:
    modules:
      - com.bm.backend.ApplicationKt.module

database:
  url: ${DB_URL}
  user: ${DB_USER}
  password: ${DB_PASSWORD}
```

### Required Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/bm_backend` |
| `DB_USER` | Database user | `bm_user` |
| `DB_PASSWORD` | Database password | `secret` |
| `ENCRYPTION_KEY` | 32-byte hex key for AES-GCM PII encryption | `0123456789abcdef...` |

The application **fails fast** on startup if `DB_URL`, `DB_USER`, or `DB_PASSWORD` are missing.

### External API URLs

The backend connects to:
- **Docling API**: `http://localhost:5000`
- **N8N Webhook**: `http://localhost:5678/webhook/fetch-user-consumption`

These are configured in `ExternalApiService.kt`. If your services run on different hosts, update:

```kotlin
private val doclingApiUrl = "http://localhost:5000"
private val n8nWebhookUrl = "http://localhost:5678/webhook/fetch-user-consumption"
```

### Nginx Configuration

The backend is accessible via:
- **Direct**: `http://217.154.181.175:8081`
- **Via nginx**: `http://217.154.181.175/api/v1/` (proxied)
- **Health check**: `http://217.154.181.175/backend/health`

## 🔄 Updating Deployment

### Update Code

```bash
# Pull latest changes (if using Git)
ssh root@217.154.181.175 "cd /root/BmBackEnd && git pull"

# Or use rsync
rsync -avz --exclude='.git' --exclude='build' ./ root@217.154.181.175:/root/BmBackEnd/
```

### Rebuild and Restart

**Systemd:**
```bash
ssh root@217.154.181.175 "cd /root/BmBackEnd && \
    ./gradlew clean shadowJar && \
    systemctl restart bm-backend"
```

**Docker:**
```bash
ssh root@217.154.181.175 "cd /root/BmBackEnd && \
    docker-compose down && \
    docker-compose up -d --build"
```

## 📊 Monitoring

### Service Status

```bash
# Check if service is running
ssh root@217.154.181.175 "systemctl is-active bm-backend"

# View detailed status
ssh root@217.154.181.175 "systemctl status bm-backend"
```

### Resource Usage

```bash
# Memory and CPU usage
ssh root@217.154.181.175 "top -bn1 | grep java"

# Docker stats
ssh root@217.154.181.175 "docker stats bm-backend --no-stream"
```

### Logs

```bash
# Last 100 lines
ssh root@217.154.181.175 "journalctl -u bm-backend -n 100"

# Follow logs in real-time
ssh root@217.154.181.175 "journalctl -u bm-backend -f"

# Docker logs
ssh root@217.154.181.175 "docker logs bm-backend --tail 100 -f"
```

## 🆘 Troubleshooting

### Service Won't Start

1. **Check logs:**
   ```bash
   ssh root@217.154.181.175 "journalctl -u bm-backend -n 50"
   ```

2. **Verify JAR exists:**
   ```bash
   ssh root@217.154.181.175 "ls -lh /root/BmBackEnd/build/libs/"
   ```

3. **Test JAR manually:**
   ```bash
   ssh root@217.154.181.175 "cd /root/BmBackEnd && java -jar build/libs/bm-backend-1.0-all.jar"
   ```

### Port Already in Use

```bash
# Find process using port 8081
ssh root@217.154.181.175 "lsof -i :8081"

# Kill process
ssh root@217.154.181.175 "kill -9 <PID>"
```

### Database Issues

```bash
# Check PostgreSQL container
ssh root@217.154.181.175 "docker ps | grep postgres"

# Connect to database
ssh root@217.154.181.175 "docker exec -it bm-postgres psql -U bm_user bm_backend"

# Check Flyway migration status
ssh root@217.154.181.175 "docker exec -it bm-postgres psql -U bm_user bm_backend -c 'SELECT * FROM flyway_schema_history ORDER BY installed_rank;'"

# Check connection pool (in app logs)
ssh root@217.154.181.175 "journalctl -u bm-backend | grep HikariPool"
```

### Cannot Connect to Docling API

```bash
# Test Docling API
ssh root@217.154.181.175 "curl http://localhost:5000/health"

# Check Docling service
ssh root@217.154.181.175 "systemctl status docling-api"
```

### Nginx Issues

```bash
# Test nginx config
ssh root@217.154.181.175 "nginx -t"

# Reload nginx
ssh root@217.154.181.175 "systemctl reload nginx"

# Check nginx logs
ssh root@217.154.181.175 "tail -f /var/log/nginx/bm-backend-error.log"
```

## 🔒 Security Considerations

### 1. Firewall Configuration

```bash
# Allow necessary ports
ssh root@217.154.181.175 "ufw allow 8081/tcp && \
    ufw allow 80/tcp && \
    ufw allow 443/tcp"
```

### 2. SSL Certificate (HTTPS)

```bash
# Install Certbot
ssh root@217.154.181.175 "apt install -y certbot python3-certbot-nginx"

# Get SSL certificate (if you have a domain)
ssh root@217.154.181.175 "certbot --nginx -d your-domain.com"
```

### 3. API Authentication

Consider adding authentication to your API endpoints. Update the Ktor configuration to include:
- JWT authentication
- API key validation
- Rate limiting (already configured)

## 📝 Environment Variables

Set environment variables in the systemd service file:

```ini
[Service]
Environment="KTOR_ENV=production"
Environment="DB_URL=jdbc:postgresql://localhost:5432/bm_backend"
Environment="DB_USER=bm_user"
Environment="DB_PASSWORD=your_secure_password"
Environment="ENCRYPTION_KEY=your_32_byte_hex_key"
```

Or in `docker-compose.yml` (already configured):

```yaml
environment:
  DB_URL: jdbc:postgresql://postgres:5432/bm_backend
  DB_USER: bm_user
  DB_PASSWORD: ${DB_PASSWORD}
  ENCRYPTION_KEY: ${ENCRYPTION_KEY}
```

## 💾 Database Backup & Restore

### Backup

```bash
# Dump the entire database
ssh root@217.154.181.175 "docker exec bm-postgres pg_dump -U bm_user bm_backend | gzip > /root/backups/bm_backend_$(date +%Y%m%d).sql.gz"
```

### Restore

```bash
# Restore from a backup
ssh root@217.154.181.175 "gunzip -c /root/backups/bm_backend_20240101.sql.gz | docker exec -i bm-postgres psql -U bm_user bm_backend"
```

### SQLite Migration (One-Time)

If migrating from a previous SQLite deployment, use the built-in migration tool:

```bash
java -cp bm-backend-1.0-all.jar com.bm.backend.tools.SqliteToPostgresMigrationKt \
  --sqlite-path /path/to/price_tables.db \
  --pg-url jdbc:postgresql://localhost:5432/bm_backend \
  --pg-user bm_user \
  --pg-password secret
```

## 🎯 Quick Reference

### Service Management

```bash
# Start service
ssh root@217.154.181.175 "systemctl start bm-backend"

# Stop service
ssh root@217.154.181.175 "systemctl stop bm-backend"

# Restart service
ssh root@217.154.181.175 "systemctl restart bm-backend"

# View status
ssh root@217.154.181.175 "systemctl status bm-backend"

# View logs
ssh root@217.154.181.175 "journalctl -u bm-backend -f"
```

### Docker Management

```bash
# Start container
ssh root@217.154.181.175 "cd /root/BmBackEnd && docker-compose up -d"

# Stop container
ssh root@217.154.181.175 "cd /root/BmBackEnd && docker-compose down"

# Restart container
ssh root@217.154.181.175 "cd /root/BmBackEnd && docker-compose restart"

# View logs
ssh root@217.154.181.175 "docker logs bm-backend -f"

# Rebuild and restart
ssh root@217.154.181.175 "cd /root/BmBackEnd && docker-compose up -d --build"
```

## 📞 Support

If you encounter issues:
1. Check the logs first
2. Verify all dependencies are running (Docling API, N8N)
3. Test endpoints manually with curl
4. Check firewall and network configuration

---

**Deployment Checklist:**
- [ ] PostgreSQL running (Docker Compose or system)
- [ ] Environment variables set (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `ENCRYPTION_KEY`)
- [ ] Build JAR successfully
- [ ] Copy files to server
- [ ] Configure systemd service or Docker
- [ ] Flyway migrations applied (automatic on startup)
- [ ] Set up nginx reverse proxy
- [ ] Test health endpoint (includes DB check)
- [ ] Test API endpoints
- [ ] Verify logs are working
- [ ] Configure firewall rules
- [ ] Set up database backups (pg_dump cron)
- [ ] Configure SSL certificate (optional)
- [ ] Migrate data from SQLite if applicable
