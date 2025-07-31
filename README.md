# Price Table Backend Service

A Kotlin-based backend service for storing and managing extracted price table data from PDF documents. This service provides RESTful APIs for storing, retrieving, and managing price table data extracted from n8n workflows or other sources.

## Features

- **RESTful API** endpoints for price table management
- **Database integration** with SQLite (development) and PostgreSQL (production ready)
- **n8n workflow integration** with comprehensive documentation
- **Data validation** and error handling
- **Rate limiting** and security features
- **Comprehensive logging** and monitoring
- **Unit and integration tests**

## Architecture

The service is built using:
- **Ktor**: Modern web framework for Kotlin
- **Exposed**: Lightweight SQL library for database operations
- **Kotlinx Serialization**: JSON serialization
- **SQLite**: Development database (PostgreSQL ready for production)
- **Logback**: Logging framework

## API Endpoints

### Store Price Tables
```http
POST /api/v1/store-price-tables
Content-Type: application/json

{
  "filename": "example.pdf",
  "extracted_tables": {
    "termino_potencia": {...},
    "termino_energia_clasica_base": {...},
    "termino_energia_clasica_unica": {...}
  },
  "source": "n8n-workflow",
  "timestamp": "2024-01-01T00:00:00Z"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Data stored successfully",
  "record_id": 123
}
```

### Get All Price Tables
```http
GET /api/v1/price-tables?limit=10&offset=0&filename=test&source=n8n
```

**Response:**
```json
{
  "success": true,
  "data": [...],
  "total": 100,
  "limit": 10,
  "offset": 0
}
```

### Get Specific Price Table
```http
GET /api/v1/price-tables/123
```

**Response:**
```json
{
  "success": true,
  "data": {
    "id": 123,
    "filename": "example.pdf",
    "extracted_tables": {...},
    "source": "n8n-workflow",
    "timestamp": "2024-01-01T00:00:00Z",
    "created_at": "2024-01-01T00:00:00Z"
  }
}
```

## Getting Started

### Prerequisites
- Java 17 or higher
- Gradle

### Installation

1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd BmBackEnd
   ```

2. **Build the project**:
   ```bash
   ./gradlew build
   ```

3. **Run the service**:
   ```bash
   ./gradlew run
   ```

4. **Access the service**:
   - Base URL: `http://localhost:8080`
   - Health Check: `http://localhost:8080/health`

### Development

#### Running Tests
```bash
# Run all tests
./gradlew test

# Run with coverage
./gradlew jacocoTestReport
```

#### Database Schema
The service automatically creates the following tables:
- `price_table_records`: Main records with metadata
- `termino_potencia`: Individual table data
- `termino_energia_clasica_base`: Individual table data
- `termino_energia_clasica_unica`: Individual table data

#### Configuration
Configuration is managed through:
- `application.yaml`: Server and database settings
- Environment variables for production

### Production Deployment

#### Environment Variables
```bash
# Database
DATABASE_URL=jdbc:postgresql://localhost:5432/pricetables
DATABASE_USER=youruser
DATABASE_PASSWORD=yourpassword

# Server
PORT=8080
HOST=0.0.0.0

# Security
RATE_LIMIT_ENABLED=true
RATE_LIMIT_GLOBAL=100
RATE_LIMIT_WINDOW=60
```

#### Docker Deployment
```dockerfile
FROM openjdk:17-jdk-slim
COPY build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

#### Systemd Service
```ini
[Unit]
Description=Price Table Backend Service
After=network.target

[Service]
Type=simple
User=pricetables
WorkingDirectory=/opt/pricetables
ExecStart=/usr/bin/java -jar /opt/pricetables/app.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

## n8n Integration

See [N8N_INTEGRATION.md](./N8N_INTEGRATION.md) for detailed instructions on configuring n8n workflows.

### Quick Setup
1. Add HTTP Request node in n8n
2. Set URL: `http://localhost:8080/api/v1/store-price-tables`
3. Set Method: POST
4. Configure body with extracted price table data

## Database Migration

### From SQLite to PostgreSQL

1. **Update dependencies** in `build.gradle.kts`:
   ```kotlin
   implementation("org.postgresql:postgresql:42.7.3")
   ```

2. **Update database configuration**:
   ```kotlin
   Database.connect(
       url = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/pricetables",
       driver = "org.postgresql.Driver",
       user = System.getenv("DATABASE_USER") ?: "postgres",
       password = System.getenv("DATABASE_PASSWORD") ?: "postgres"
   )
   ```

3. **Run migrations** (if using Flyway or similar)

## Monitoring and Logging

### Health Check
```bash
curl http://localhost:8080/health
```

### Logs
Logs are written to console and can be configured in `logback.xml`:
- Application logs: INFO level
- Database queries: DEBUG level (development)
- Error logs: ERROR level

### Metrics (Future Enhancement)
- Prometheus metrics endpoint
- Database connection pool monitoring
- API response time tracking

## Security Features

- **Rate limiting**: 100 requests per minute globally
- **Input validation**: All requests validated before processing
- **SQL injection prevention**: Using parameterized queries
- **CORS configuration**: Configurable for production
- **Authentication ready**: Bearer token or API key support can be added

## API Response Codes

- `200 OK`: Successful GET requests
- `201 Created`: Successful POST requests
- `400 Bad Request`: Invalid request data
- `404 Not Found`: Resource not found
- `429 Too Many Requests`: Rate limit exceeded
- `500 Internal Server Error`: Server errors

## Testing

### Unit Tests
```bash
./gradlew test --tests "*PriceTableServiceTest"
```

### Integration Tests
```bash
./gradlew test --tests "*ApplicationTest"
```

### Load Testing
```bash
# Using Apache Bench
ab -n 1000 -c 10 -p test-payload.json -T application/json http://localhost:8080/api/v1/store-price-tables

# Using curl for simple testing
curl -X POST http://localhost:8080/api/v1/store-price-tables \
  -H "Content-Type: application/json" \
  -d @test-payload.json
```

## Troubleshooting

### Common Issues

1. **Port already in use**:
   ```bash
   # Check what's using port 8080
   lsof -i :8080
   # Kill process or change port in application.yaml
   ```

2. **Database locked**:
   ```bash
   # Ensure no other process is using the database
   lsof price_tables.db
   ```

3. **Out of memory**:
   ```bash
   # Increase JVM heap size
   export JAVA_OPTS="-Xmx1g -Xms512m"
   ```

### Debug Mode
```bash
# Enable debug logging
export LOG_LEVEL=DEBUG
./gradlew run
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For support, please:
1. Check the troubleshooting section
2. Review the logs for error messages
3. Open an issue with detailed information

## Changelog

### v1.0.0
- Initial release
- Basic CRUD operations
- n8n integration
- SQLite database support
- Rate limiting and validation
- Comprehensive test suite
