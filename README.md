# BM Backend - Price Table & Consumption Management API

Kotlin/Ktor REST API for managing price tables and user consumption data with PDF extraction capabilities.

## 🚀 Features

- **Price Table Management**: CRUD operations for electricity price tables
- **User Consumption Processing**: Upload and process consumption PDFs
- **Firebase Authentication**: Token verification with encrypted PII storage (AES-GCM)
- **External API Integration**: 
  - Docling API for PDF data extraction
  - N8N workflows for automated processing
- **PostgreSQL Database**: Production-grade storage with HikariCP connection pooling
- **Flyway Migrations**: Versioned, repeatable schema management
- **Rate Limiting**: Built-in request throttling
- **Health Checks**: DB-aware monitoring endpoints

## 📋 Tech Stack

- **Language**: Kotlin 2.0.21
- **Framework**: Ktor 2.3.12
- **Database**: PostgreSQL 16 with Exposed ORM & HikariCP
- **Migrations**: Flyway 10.21
- **Build Tool**: Gradle 8.13
- **Runtime**: JVM 17
- **Testing**: JUnit 5, Testcontainers (PostgreSQL)

## 🏃 Quick Start

### Local Development

```bash
# Build and run
./gradlew run

# Access API
curl http://localhost:8081/health
```

### Deploy to Server

See [QUICK_DEPLOY.md](QUICK_DEPLOY.md) for fast deployment to **217.154.181.175**.

```bash
chmod +x deploy-remote.sh
./deploy-remote.sh
```

## 📚 Documentation

- **[DEPLOYMENT.md](DEPLOYMENT.md)**: Complete deployment guide
- **[QUICK_DEPLOY.md](QUICK_DEPLOY.md)**: Fast deployment instructions

## 🔌 API Endpoints

### Health & Status
- `GET /health` - Health check
- `GET /` - Service info

### Price Tables
- `GET /api/v1/price-tables` - List all price tables
- `GET /api/v1/price-tables/{id}` - Get specific price table
- `POST /api/v1/price-tables` - Create price table
- `PUT /api/v1/price-tables/{id}` - Update price table
- `DELETE /api/v1/price-tables/{id}` - Delete price table

### User Consumption
- `POST /api/v1/user-consumption/upload` - Upload consumption PDF
- `GET /api/v1/user-consumption/jobs/{id}` - Get job status

## 🔧 Configuration

### Required Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/bm_backend` |
| `DB_USER` | Database user | `bm_user` |
| `DB_PASSWORD` | Database password | `secret` |
| `ENCRYPTION_KEY` | 32-byte hex key for AES-GCM PII encryption | `0123456789abcdef...` |

### application.yaml

```yaml
ktor:
  deployment:
    port: 8081
    host: 0.0.0.0

database:
  url: ${DB_URL}
  user: ${DB_USER}
  password: ${DB_PASSWORD}
```

## 🏗️ Project Structure

```
src/main/kotlin/com/bm/backend/
├── Application.kt              # Main application entry
├── database/                   # Database configuration (DataSourceFactory, DatabaseFactory)
├── models/                     # Data models (one file per model)
├── repositories/               # Data access layer (Exposed + raw SQL)
│   └── ports/                  # Repository interfaces (Clean Architecture)
├── routes/                     # API routes
├── security/                   # Firebase auth, AES-GCM encryption
├── services/                   # Business logic (depends on ports)
└── tools/                      # CLI utilities (SQLite-to-Postgres migration)

src/main/resources/
├── application.yaml            # Ktor + DB config
└── db/migration/postgres/      # Flyway migrations (V1-V3)
```

## 🧪 Testing

```bash
# Requires Docker running (Testcontainers spins up PostgreSQL)
./gradlew test

# Tests skip gracefully if Docker is unavailable
```

## 📦 Building

```bash
# Build JAR
./gradlew shadowJar

# Output: build/libs/bm-backend-1.0-all.jar
```

## 🐳 Docker

```bash
# Start PostgreSQL + backend
docker-compose up -d

# The compose file includes:
# - postgres:16-alpine (port 5432) with healthcheck
# - bm-backend (port 8081) with depends_on healthcheck
```

## 🔗 Dependencies

This backend integrates with:
- **Docling API** (port 5000): PDF extraction service
- **N8N** (port 5678): Workflow automation

## 📝 License

Private project - B&M

## 👤 Author

Victor - VictorGeoSoftware
