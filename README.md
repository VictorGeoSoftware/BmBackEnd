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
  "message": "Price tables stored successfully",
  "stored_tables": ["termino_potencia", "termino_energia_clasica_base", "termino_energia_clasica_unica"],
  "id": "uuid-generated-id"
}
```

### Retrieve Price Tables
```http
GET /api/v1/price-tables?filename=example.pdf&limit=10&offset=0
```

**Response:**
```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "filename": "example.pdf",
      "extracted_tables": {...},
      "source": "n8n-workflow",
      "timestamp": "2024-01-01T00:00:00Z",
      "created_at": "2024-01-01T00:00:00Z"
    }
  ],
  "total": 1,
  "limit": 10,
  "offset": 0
}
```

### Health Check
```http
GET /health
```

**Response:**
```json
{
  "status": "healthy",
  "timestamp": "2024-01-01T00:00:00Z",
  "version": "1.0.0"
}
```

## Database Schema

### price_tables
| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| filename | VARCHAR(255) | Source PDF filename |
| extracted_tables | TEXT | JSON data of extracted tables |
| source | VARCHAR(100) | Source system (e.g., "n8n-workflow") |
| timestamp | TIMESTAMP | When data was extracted |
| created_at | TIMESTAMP | When record was created |
| updated_at | TIMESTAMP | When record was last updated |

## Getting Started

### Prerequisites
- Java 11 or higher
- Gradle 7.0 or higher
- SQLite (for development)

### Installation

1. Clone the repository:
```bash
git clone <repository-url>
cd BmBackEnd
```

2. Build the project:
```bash
./gradlew build
```

3. Run the application:
```bash
./gradlew run
```

The service will start on `http://localhost:8080`

### Configuration

The application can be configured through environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 8080 | Server port |
| `DATABASE_URL` | SQLite file | Database connection string |
| `LOG_LEVEL` | INFO | Logging level |
| `RATE_LIMIT_REQUESTS` | 100 | Requests per minute per IP |

### Development

#### Running Tests
```bash
./gradlew test
```

#### Database Migrations
The application automatically creates the necessary database schema on startup.

#### API Documentation
Once running, visit `http://localhost:8080/docs` for interactive API documentation.

## n8n Integration

This service is designed to work seamlessly with n8n workflows for PDF processing. See `N8N_INTEGRATION.md` for detailed integration instructions.

### Example n8n Workflow
1. **PDF Input**: Receive PDF file
2. **Extract Tables**: Process PDF and extract price tables
3. **HTTP Request**: Send extracted data to this service
4. **Store Data**: Data is validated and stored in the database

## Production Deployment

### Docker
```dockerfile
FROM openjdk:11-jre-slim
COPY build/libs/BmBackEnd-all.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

### Environment Setup
- Use PostgreSQL for production database
- Configure proper logging levels
- Set up monitoring and health checks
- Implement proper security measures

## Security

- **Rate Limiting**: Prevents API abuse
- **Input Validation**: All inputs are validated
- **Error Handling**: Secure error messages
- **CORS**: Configurable CORS policies

## Monitoring

### Health Checks
- `/health` endpoint for basic health monitoring
- Database connectivity checks
- Memory and performance metrics

### Logging
- Structured logging with Logback
- Request/response logging
- Error tracking and alerting

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For questions or issues, please contact the development team or create an issue in the repository.

---

## Future Enhancements

### Planned Features
- **Advanced Search**: Full-text search capabilities
- **Data Export**: Export data in various formats (CSV, Excel)
- **Batch Operations**: Bulk data operations
- **User Authentication**: Role-based access control
- **Audit Logging**: Track all data changes
- **Real-time Notifications**: WebSocket support for real-time updates

### Performance Optimizations
- Database indexing strategies
- Caching layer implementation
- Connection pooling optimization
- Async processing for large datasets

### Metrics (Future Enhancement)
- Prometheus metrics endpoint
- Database connection pool monitoring
- API response time tracking

## Troubleshooting

### Common Issues

#### Database Connection Issues
- Verify database URL and credentials
- Check network connectivity
- Ensure database server is running

#### High Memory Usage
- Monitor JVM heap size
- Check for memory leaks in long-running processes
- Optimize database queries

#### Slow API Responses
- Check database query performance
- Monitor network latency
- Review application logs for bottlenecks

### Debug Mode
Enable debug logging by setting `LOG_LEVEL=DEBUG` environment variable.

### Performance Monitoring
Use JVM monitoring tools like JProfiler or VisualVM for detailed performance analysis.
