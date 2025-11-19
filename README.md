# BM Backend - Price Table & Consumption Management API

Kotlin/Ktor REST API for managing price tables and user consumption data with PDF extraction capabilities.

## 🚀 Features

- **Price Table Management**: CRUD operations for electricity price tables
- **User Consumption Processing**: Upload and process consumption PDFs
- **External API Integration**: 
  - Docling API for PDF data extraction
  - N8N workflows for automated processing
- **SQLite Database**: Lightweight, file-based storage
- **Rate Limiting**: Built-in request throttling
- **Health Checks**: Monitoring endpoints

## 📋 Tech Stack

- **Language**: Kotlin 2.0.21
- **Framework**: Ktor 2.3.12
- **Database**: SQLite with Exposed ORM
- **Build Tool**: Gradle 8.5
- **Runtime**: JVM 17

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

Edit `src/main/resources/application.yaml`:

```yaml
ktor:
  deployment:
    port: 8081
    host: 0.0.0.0

database:
  url: "jdbc:sqlite:price_tables.db"
```

## 🏗️ Project Structure

```
src/main/kotlin/com/bm/backend/
├── Application.kt              # Main application entry
├── database/                   # Database configuration
├── models/                     # Data models
├── repositories/               # Data access layer
├── routes/                     # API routes
└── services/                   # Business logic
```

## 🧪 Testing

```bash
./gradlew test
```

## 📦 Building

```bash
# Build JAR
./gradlew shadowJar

# Output: build/libs/bm-backend-1.0-all.jar
```

## 🐳 Docker

```bash
# Build image
docker build -t bm-backend .

# Run container
docker-compose up -d
```

## 🔗 Dependencies

This backend integrates with:
- **Docling API** (port 5000): PDF extraction service
- **N8N** (port 5678): Workflow automation

## 📝 License

Private project - B&M

## 👤 Author

Victor - VictorGeoSoftware
