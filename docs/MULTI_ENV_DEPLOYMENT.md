# BM Multi-Environment Deployment Guide

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Phase 1: Local Docker Compose](#phase-1-local-docker-compose)
3. [Phase 2: QA Environment on VPS](#phase-2-qa-environment-on-vps)
4. [Client Configuration](#client-configuration)
5. [RAM & Resource Estimates](#ram--resource-estimates)

---

## Architecture Overview

### Current state (single PROD environment)

```
┌─────────────────────────────────────────────────────────────────────┐
│  VPS: 217.154.181.175                                               │
│                                                                     │
│  ┌──────────┐         ┌───────────────┐       ┌─────────────────┐  │
│  │  Nginx   │────────▶│  Backend :8081│──────▶│ Docling API :5000│  │
│  │  :80/443 │         │  (Ktor/JVM)   │──┐   └─────────────────┘  │
│  └──────────┘         └───────────────┘  │   ┌─────────────────┐  │
│       │                                   └──▶│ Docling PT :5001 │  │
│       │               ┌───────────────┐       └─────────────────┘  │
│       └──────────────▶│  n8n :5678    │──────▶ (also calls Docling)│
│                       └───────────────┘                             │
└─────────────────────────────────────────────────────────────────────┘

         ▲                        ▲
         │                        │
┌────────┴───────┐    ┌──────────┴──────────────┐
│  Android App   │    │  Web (Firebase Hosting)  │
│  hits :8081    │    │  API routes → :8081      │
│  directly      │    │  (server-side)           │
└────────────────┘    └─────────────────────────┘
```

### Target state (PROD + QA, shared Docling)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  VPS: 217.154.181.175                                                    │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │  SHARED (one copy, used by all environments)                        │ │
│  │                                                                     │ │
│  │  ┌─────────────────┐  ┌─────────────────┐                       │ │
│  │  │ Docling API:5000│  │ Docling PT:5001 │                       │ │
│  │  └─────────────────┘  └─────────────────┘                       │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
│           ▲          ▲                                                    │
│           │          │                                                    │
│  ┌────────┴──────────┴───┐  ┌──────┴─────────┴───┐                      │
│  │  PROD                  │  │  QA                  │                     │
│  │                        │  │                      │                     │
│  │  Backend :8081         │  │  Backend :9081       │                     │
│  │  n8n     :5678         │  │  n8n     :6678       │                     │
│  │  Postgres: bm_backend  │  │  Postgres: bm_qa     │                     │
│  └────────────────────────┘  └──────────────────────┘                    │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │  Nginx :80/443                                                    │    │
│  │                                                                   │    │
│  │  api.bm.com/api/v1/*       → 127.0.0.1:8081  (PROD)             │    │
│  │  qa.bm.com/api/v1/*        → 127.0.0.1:9081  (QA)               │    │
│  │  n8n.bm.com               → 127.0.0.1:5678  (PROD)              │    │
│  │  n8n-qa.bm.com            → 127.0.0.1:6678  (QA)                │    │
│  └──────────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────┘
```

### Design decisions

| Component | Shared or isolated? | Why |
|---|---|---|
| Docling (both APIs) | **Shared** | Stateless, heavy (~4GB RAM + ~10GB disk). No benefit in duplicating. |
| Backend (Ktor) | **Isolated** | Stateful (connected to its own DB). Different versions may be deployed. |
| n8n | **Isolated** | Has its own workflows, webhooks, and data. |
| Postgres | **Isolated** | Separate databases per environment. Same Postgres instance, different DBs. |
| Nginx | **Shared** | Single reverse proxy, routes by subdomain. |

---

## Phase 1: Local Docker Compose

Goal: run the entire BM stack locally with Docker Compose, using shared images efficiently.

> **⚠️ Environment note — local machine vs. VPS paths**
>
> This plan was written with an idealized `BM/{Backend,n8n,Web}/` layout, but the actual
> repositories do not follow those names. The whole migration is intended to be driven from
> **this development machine**, whose layout differs from the VPS. Keep these mappings in mind
> wherever the plan references a path, build context, or directory:
>
> | Plan reference | This machine (local) | VPS (`217.154.181.175`) |
> |---|---|---|
> | Workspace root | `/home/a510301/Documents/Personal/B&M/` | `/opt/bm/` (target) |
> | `Backend/` | `BmBackEnd/` | `BmBackEnd/` |
> | `n8n/` (Docling build context) | `DoclingBillReader/` | `DoclingBillReader/` |
> | `Web/` | `BmWeb/` | n/a (Firebase Hosting) |
> | Android app | `BmApp/` | n/a (client) |
> | `BM/docker/` (orchestration) | `docker/` at the workspace root (to be created) | `/opt/bm/docker/` |
>
> Note the literal directory name on this machine is `B&M` (with an ampersand). When scripting,
> always quote the path (`"/home/a510301/Documents/Personal/B&M/..."`) so the shell does not
> interpret `&`. Any `cd`, `rsync`, or `docker compose` command copied verbatim from this plan
> must be re-pointed at the real folders above before running it.

### 1.1 Directory structure

```
B&M/                                    # workspace root on this machine
├── BmBackEnd/                          # backend (build context for bm/backend)
├── DoclingBillReader/                  # Docling + n8n sources (build context for bm/docling)
├── BmWeb/                              # Next.js web client
├── BmApp/                              # Android client
└── docker/                            # NEW — orchestration layer (to be created)
    ├── docker-compose.yml             # shared infra (Docling, Postgres, Nginx)
    ├── docker-compose.prod.yml        # PROD overrides (ports, DB, env)
    ├── docker-compose.qa.yml          # QA overrides (ports, DB, env)
    ├── .env.prod                      # PROD environment variables
    ├── .env.qa                        # QA environment variables
    └── nginx/
        └── nginx.conf                 # local Nginx config
```

### 1.2 Shared base: `docker/docker-compose.yml`

This defines the shared infrastructure and all service definitions. Services that need per-environment overrides get their env/ports from the override files.

```yaml
services:
  # ─── SHARED: Docling (built once, used by all environments) ───

  docling-api:
    image: bm/docling:latest
    build:
      context: ../DoclingBillReader
      dockerfile: Dockerfile
    command: ["python", "docling_customer_data_extraction_api_server.py"]
    ports:
      - "5000:5000"
    volumes:
      - docling-models:/app/models
      - docling-temp:/app/temp
      - docling-uploads:/app/uploads
      - docling-logs:/app/logs
    environment:
      - FLASK_ENV=production
      - LOG_LEVEL=INFO
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:5000/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

  docling-price-tables:
    image: bm/docling:latest
    command: ["python", "docling_price_tables_extraction_api_server.py"]
    ports:
      - "5001:5001"
    volumes:
      - docling-models:/app/models
      - docling-temp:/app/temp
      - docling-uploads:/app/uploads
      - docling-logs:/app/logs
    environment:
      - FLASK_ENV=production
      - LOG_LEVEL=INFO
      - PORT=5001
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:5001/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

  # ─── SHARED: Postgres (single instance, multiple databases) ───

  postgres:
    image: postgres:16-alpine
    ports:
      - "5433:5432"
    environment:
      POSTGRES_USER: bm_app
      POSTGRES_PASSWORD: ${DB_PASSWORD:-changeme}
    volumes:
      - pg-data:/var/lib/postgresql/data
      - ./init-databases.sql:/docker-entrypoint-initdb.d/init.sql:ro
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U bm_app"]
      interval: 10s
      timeout: 5s
      retries: 5

  # ─── PROD: Backend ───

  backend-prod:
    image: bm/backend:latest
    build:
      context: ../BmBackEnd
      dockerfile: Dockerfile
    ports:
      - "8081:8081"
    environment:
      - KTOR_ENV=production
      - DB_URL=jdbc:postgresql://postgres:5432/bm_backend?sslmode=disable
      - DB_USER=bm_app
      - DB_PASSWORD=${DB_PASSWORD:-changeme}
      - BM_ENCRYPTION_KEY=${BM_ENCRYPTION_KEY:?BM_ENCRYPTION_KEY must be set}
      - DOCLING_CUSTOMER_API_URL=http://docling-api:5000
      - DOCLING_PRICE_TABLES_API_URL=http://docling-price-tables:5001
      - N8N_FETCH_USER_CONSUMPTION_WEBHOOK_URL=http://n8n-prod:5678/webhook/fetch-user-consumption
      - N8N_FETCH_TOTAL_PRICES_WEBHOOK_URL=http://n8n-prod:5678/webhook/fetch-total-prices
      - FIREBASE_SERVICE_ACCOUNT_PATH=/app/firebase-service-account.json
    volumes:
      - ../BmBackEnd/firebase-service-account.json:/app/firebase-service-account.json:ro
    depends_on:
      postgres:
        condition: service_healthy
      docling-api:
        condition: service_healthy
    restart: unless-stopped

  # ─── PROD: n8n ───

  n8n-prod:
    image: n8nio/n8n:1.117.3
    ports:
      - "5678:5678"
    environment:
      - N8N_BASIC_AUTH_ACTIVE=true
      - N8N_BASIC_AUTH_USER=admin
      - N8N_BASIC_AUTH_PASSWORD=${N8N_PROD_PASSWORD:-n8n-admin}
      - DOCLING_API_URL=http://docling-api:5000
      - WEBHOOK_URL=http://localhost:5678/
      - N8N_HOST=localhost
      - N8N_PORT=5678
      - N8N_PROTOCOL=http
      - NODE_ENV=production
      - N8N_SECURE_COOKIE=false
      - DB_TYPE=sqlite
    volumes:
      - n8n-prod-data:/home/node/.n8n
    depends_on:
      docling-api:
        condition: service_healthy
    restart: unless-stopped

  # ─── QA: Backend ───

  backend-qa:
    image: bm/backend:latest       # same image as PROD, no rebuild
    ports:
      - "9081:8081"
    environment:
      - KTOR_ENV=production
      - DB_URL=jdbc:postgresql://postgres:5432/bm_qa?sslmode=disable   # different database
      - DB_USER=bm_app
      - DB_PASSWORD=${DB_PASSWORD:-changeme}
      - BM_ENCRYPTION_KEY=${BM_ENCRYPTION_KEY:?BM_ENCRYPTION_KEY must be set}
      - DOCLING_CUSTOMER_API_URL=http://docling-api:5000
      - DOCLING_PRICE_TABLES_API_URL=http://docling-price-tables:5001
      - N8N_FETCH_USER_CONSUMPTION_WEBHOOK_URL=http://n8n-qa:5678/webhook/fetch-user-consumption
      - N8N_FETCH_TOTAL_PRICES_WEBHOOK_URL=http://n8n-qa:5678/webhook/fetch-total-prices
      - FIREBASE_SERVICE_ACCOUNT_PATH=/app/firebase-service-account.json
    volumes:
      - ../BmBackEnd/firebase-service-account.json:/app/firebase-service-account.json:ro
    depends_on:
      postgres:
        condition: service_healthy
      docling-api:
        condition: service_healthy
    restart: unless-stopped

  # ─── QA: n8n ───

  n8n-qa:
    image: n8nio/n8n:1.117.3       # same image as PROD
    ports:
      - "6678:5678"
    environment:
      - N8N_BASIC_AUTH_ACTIVE=true
      - N8N_BASIC_AUTH_USER=admin
      - N8N_BASIC_AUTH_PASSWORD=${N8N_QA_PASSWORD:-n8n-qa-admin}
      - DOCLING_API_URL=http://docling-api:5000
      - WEBHOOK_URL=http://localhost:6678/
      - N8N_HOST=localhost
      - N8N_PORT=5678
      - N8N_PROTOCOL=http
      - NODE_ENV=production
      - N8N_SECURE_COOKIE=false
      - DB_TYPE=sqlite
    volumes:
      - n8n-qa-data:/home/node/.n8n
    depends_on:
      docling-api:
        condition: service_healthy
    restart: unless-stopped

volumes:
  docling-models:
  docling-temp:
  docling-uploads:
  docling-logs:
  pg-data:
  n8n-prod-data:
  n8n-qa-data:
```

### 1.3 Postgres init script: `docker/init-databases.sql`

This runs once when the Postgres container is first created, creating both databases:

```sql
-- Create PROD database
CREATE DATABASE bm_backend;

-- Create QA database
CREATE DATABASE bm_qa;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE bm_backend TO bm_app;
GRANT ALL PRIVILEGES ON DATABASE bm_qa TO bm_app;
```

### 1.4 Environment file: `docker/.env`

```bash
# Shared
DB_PASSWORD=your-secure-password-here

# Backend PII encryption (base64-encoded 32-byte key, AES-GCM). REQUIRED — do not lose it,
# or previously-encrypted data becomes unreadable. Shared by PROD and QA backends.
# Generate with: openssl rand -base64 32
BM_ENCRYPTION_KEY=your-base64-32-byte-key-here

# PROD n8n
N8N_PROD_PASSWORD=n8n-admin-prod

# QA n8n
N8N_QA_PASSWORD=n8n-admin-qa
```

### 1.5 Usage commands

```bash
# Navigate to the docker directory
cd BM/docker

# Build images (only needed once, or when code changes)
docker compose build

# Start everything (PROD + QA + shared infra)
docker compose up -d

# Start only PROD (shared infra starts automatically via depends_on)
docker compose up -d backend-prod n8n-prod

# Start only QA
docker compose up -d backend-qa n8n-qa

# Stop QA without affecting PROD
docker compose stop backend-qa n8n-qa

# View logs for a specific service
docker compose logs -f backend-prod

# Rebuild only the backend after code changes
docker compose build backend-prod
# backend-qa uses the same image, so it gets the update too

# Tear down everything
docker compose down

# Tear down everything AND delete all data (careful!)
docker compose down -v
```

### 1.6 Port map (local)

| Service | URL |
|---|---|
| PROD Backend | `http://localhost:8081/api/v1` |
| QA Backend | `http://localhost:9081/api/v1` |
| PROD n8n | `http://localhost:5678` |
| QA n8n | `http://localhost:6678` |
| Docling API (shared) | `http://localhost:5000` |
| Docling Price Tables (shared) | `http://localhost:5001` |
| Postgres (shared) | `localhost:5433` |

### 1.7 How the image sharing works

```
docker compose build
  │
  ├─ docling-api has build: + image: bm/docling:latest
  │  → builds Dockerfile, tags as bm/docling:latest (~10GB, done ONCE)
  │
  ├─ docling-price-tables has image: bm/docling:latest (no build:)
  │  → reuses bm/docling:latest, NO rebuild
  │
  ├─ backend-prod has build: + image: bm/backend:latest
  │  → builds Backend/Dockerfile, tags as bm/backend:latest (done ONCE)
  │
  ├─ backend-qa has image: bm/backend:latest (no build:)
  │  → reuses bm/backend:latest, NO rebuild
  │
  ├─ n8n-prod has image: n8nio/n8n:1.117.3
  │  → pulls from Docker Hub ONCE
  │
  └─ n8n-qa has image: n8nio/n8n:1.117.3
     → already pulled, NO re-download
```

**Total disk usage:** ~10GB Docling + ~200MB Backend + ~800MB n8n + ~100MB Postgres = ~11GB once (not doubled).

---

## Phase 2: QA Environment on VPS

After validating locally, deploy the same setup to your VPS at `217.154.181.175`.

### 2.1 Prerequisites

- Docker and Docker Compose installed on the VPS
- DNS records pointing to your VPS:
  - `api.yourdomain.com` → `217.154.181.175` (PROD backend)
  - `qa.yourdomain.com` → `217.154.181.175` (QA backend)
  - `n8n.yourdomain.com` → `217.154.181.175` (PROD n8n)
  - `n8n-qa.yourdomain.com` → `217.154.181.175` (QA n8n)
- Or use subpaths instead of subdomains if you don't have a domain

### 2.2 Nginx config for VPS: `docker/nginx/nginx.conf`

```nginx
events {
    worker_connections 1024;
}

http {
    # ─── PROD Backend ───
    server {
        listen 80;
        server_name api.yourdomain.com;

        location /api/v1/ {
            proxy_pass http://backend-prod:8081/api/v1/;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            client_max_body_size 50M;
        }

        location /health {
            proxy_pass http://backend-prod:8081/health;
        }
    }

    # ─── QA Backend ───
    server {
        listen 80;
        server_name qa.yourdomain.com;

        location /api/v1/ {
            proxy_pass http://backend-qa:8081/api/v1/;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            client_max_body_size 50M;
        }

        location /health {
            proxy_pass http://backend-qa:8081/health;
        }
    }

    # ─── PROD n8n ───
    server {
        listen 80;
        server_name n8n.yourdomain.com;

        location / {
            proxy_pass http://n8n-prod:5678/;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "upgrade";
        }
    }

    # ─── QA n8n ───
    server {
        listen 80;
        server_name n8n-qa.yourdomain.com;

        location / {
            proxy_pass http://n8n-qa:5678/;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "upgrade";
        }
    }
}
```

Add the Nginx service to the compose file for VPS deployment:

```yaml
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      - backend-prod
      - backend-qa
      - n8n-prod
      - n8n-qa
    restart: unless-stopped
```

### 2.3 VPS deployment steps

```bash
# 1. SSH into VPS
ssh root@217.154.181.175

# 2. Clone or pull the repo
cd /opt/bm
git pull

# 3. Build images (only once, or on code changes)
cd docker
docker compose build

# 4. Start everything
docker compose up -d

# 5. Verify all services are running
docker compose ps

# 6. Check health
curl http://localhost:8081/health    # PROD backend
curl http://localhost:9081/health    # QA backend
curl http://localhost:5000/health    # Docling
curl http://localhost:5001/health    # Docling Price Tables
```

### 2.4 SSL with Let's Encrypt (optional but recommended)

For HTTPS, the simplest approach is to use Certbot on the host and mount certs into the Nginx container, or switch to Traefik which handles SSL automatically:

```bash
# On the VPS host (not in Docker)
apt install certbot
certbot certonly --standalone -d api.yourdomain.com -d qa.yourdomain.com \
  -d n8n.yourdomain.com -d n8n-qa.yourdomain.com

# Mount certs into Nginx container
# volumes:
#   - /etc/letsencrypt:/etc/letsencrypt:ro
```

---

## Client Configuration

### Web (Next.js)

No code changes needed. The backend URL is already read from an environment variable.

| Environment | Config location | Value |
|---|---|---|
| Local dev | `Web/.env.local` | `BM_BACKEND_URL=http://localhost:8081` |
| PROD | Firebase env / `apphosting.yaml` | `BM_BACKEND_URL=https://api.yourdomain.com` |
| QA | Firebase env / `apphosting.yaml` | `BM_BACKEND_URL=https://qa.yourdomain.com` |

For QA, you can either:
- Use a second Firebase project
- Use Firebase preview channels (`firebase hosting:channel:deploy qa`)
- Set the env var at build time

### Android App (BmApp)

Current config in `BmApp/data/build.gradle.kts`:

```kotlin
// Current
release { buildConfigField("String", "API_BASE_URL", "\"http://217.154.181.175:8081/api/v1\"") }
debug   { buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:8081/api/v1\"") }
```

**Option A: Use debug/release split (simple)**

```kotlin
release { buildConfigField("String", "API_BASE_URL", "\"https://api.yourdomain.com/api/v1\"") }
debug   { buildConfigField("String", "API_BASE_URL", "\"https://qa.yourdomain.com/api/v1\"") }
```

Debug builds automatically hit QA, release builds hit PROD.

**Option B: Add product flavors (more control)**

```kotlin
flavorDimensions += "environment"
productFlavors {
    create("prod") {
        dimension = "environment"
        buildConfigField("String", "API_BASE_URL", "\"https://api.yourdomain.com/api/v1\"")
    }
    create("qa") {
        dimension = "environment"
        buildConfigField("String", "API_BASE_URL", "\"https://qa.yourdomain.com/api/v1\"")
        applicationIdSuffix = ".qa"  // allows installing both on same device
    }
}
```

This gives you build variants like `qaDebug`, `qaRelease`, `prodDebug`, `prodRelease`.

---

## RAM & Resource Estimates

### Per-component RAM usage

| Component | Instances | RAM each | Total |
|---|---|---|---|
| Docling API (shared) | 1 | ~2 GB | 2 GB |
| Docling Price Tables (shared) | 1 | ~2 GB | 2 GB |
| Backend PROD | 1 | ~512 MB | 512 MB |
| Backend QA | 1 | ~512 MB | 512 MB |
| n8n PROD | 1 | ~256 MB | 256 MB |
| n8n QA | 1 | ~256 MB | 256 MB |
| Postgres (shared) | 1 | ~512 MB | 512 MB |
| Nginx (shared) | 1 | ~50 MB | 50 MB |
| OS + overhead | - | - | ~1 GB |
| **Total** | | | **~7 GB** |

**Your VPS has 24GB RAM -- 17GB headroom remaining.**

### Disk usage

| Component | Size |
|---|---|
| Docling image (shared) | ~10 GB |
| Backend image (shared) | ~200 MB |
| n8n image (shared) | ~800 MB |
| Postgres image | ~100 MB |
| ML models volume | ~2-4 GB |
| Database data | variable |
| **Total base** | **~15 GB** |

**Your VPS has 720GB -- not a concern.**

---

## Quick Reference

### Port map

| Service | PROD | QA |
|---|---|---|
| Backend | `:8081` | `:9081` |
| n8n | `:5678` | `:6678` |
| Docling API | `:5000` (shared) | `:5000` (shared) |
| Docling Price Tables | `:5001` (shared) | `:5001` (shared) |
| Postgres | `:5433` (shared) | `:5433` (shared) |

### Common commands

```bash
# Build all images
docker compose build

# Start everything
docker compose up -d

# Start only PROD services
docker compose up -d backend-prod n8n-prod

# Start only QA services
docker compose up -d backend-qa n8n-qa

# Stop QA without touching PROD
docker compose stop backend-qa n8n-qa

# Rebuild backend after code changes (both envs get it)
docker compose build backend-prod

# View PROD backend logs
docker compose logs -f backend-prod

# Check what's running
docker compose ps

# Nuclear option: stop everything, delete all data
docker compose down -v
```
