#!/bin/bash

# Remote deployment script for BM Backend (Kotlin/Ktor)
# Deploys to the same server as DoclingBillReader

set -e

# Configuration - modify these variables
REMOTE_SERVER="root@217.154.181.175"
REMOTE_PATH="/root/BmBackEnd"
DOMAIN="217.154.181.175"
USE_DOCKER=false  # Using systemd for GitHub Actions compatibility
GITHUB_REPO="https://github.com/VictorGeoSoftware/BmBackEnd"
GIT_BRANCH="main"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "🚀 Starting remote deployment of BM Backend to $REMOTE_SERVER..."

# Function to print colored output
print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if required files exist
check_files() {
    print_status "Checking required files..."
    
    required_files=("build.gradle.kts" "src" "Dockerfile")
    
    for file in "${required_files[@]}"; do
        if [ ! -e "$file" ]; then
            print_error "Required file/directory not found: $file"
            exit 1
        fi
    done
    
    print_status "All required files found"
}

# Check server prerequisites
check_server_prerequisites() {
    print_status "Checking server prerequisites..."
    
    # Check if Java is installed
    if ! ssh "$REMOTE_SERVER" "command -v java > /dev/null 2>&1"; then
        print_warning "Java not found on server. Installing OpenJDK 17..."
        ssh "$REMOTE_SERVER" "apt update && apt install -y openjdk-17-jdk"
    else
        print_status "Java is installed"
        ssh "$REMOTE_SERVER" "java -version"
    fi
    
    # Check if git is installed (needed for GitHub deployment)
    if [ -n "$GITHUB_REPO" ]; then
        if ! ssh "$REMOTE_SERVER" "command -v git > /dev/null 2>&1"; then
            print_warning "Git not found on server. Installing..."
            ssh "$REMOTE_SERVER" "apt update && apt install -y git"
        else
            print_status "Git is installed"
        fi
    fi
    
    print_status "Server prerequisites check complete"
}

# Build JAR locally
build_jar() {
    print_status "Building JAR locally..."
    
    ./gradlew clean shadowJar --no-daemon
    
    if [ ! -f "build/libs/bm-backend-1.0-all.jar" ]; then
        print_error "JAR file not found after build"
        exit 1
    fi
    
    print_status "JAR built successfully"
}

# Deploy to remote server using Git
deploy_remote_git() {
    print_status "Deploying to remote server using Git..."
    
    if [ -z "$GITHUB_REPO" ]; then
        print_error "GITHUB_REPO is not set. Please set it in the script or use rsync deployment."
        exit 1
    fi
    
    # Set up SSH for GitHub on the server
    print_status "Setting up GitHub SSH access..."
    ssh "$REMOTE_SERVER" "mkdir -p ~/.ssh && ssh-keyscan github.com >> ~/.ssh/known_hosts 2>/dev/null"
    
    # Convert HTTPS URL to SSH URL if needed
    SSH_REPO=$(echo "$GITHUB_REPO" | sed 's|https://github.com/|git@github.com:|')
    
    # Check if directory exists and has git repo
    if ssh "$REMOTE_SERVER" "[ -d $REMOTE_PATH/.git ]"; then
        print_status "Repository exists, pulling latest changes..."
        ssh "$REMOTE_SERVER" "cd $REMOTE_PATH && git fetch origin && git reset --hard origin/$GIT_BRANCH"
    else
        print_status "Cloning repository..."
        ssh "$REMOTE_SERVER" "rm -rf $REMOTE_PATH && git clone -b $GIT_BRANCH $SSH_REPO $REMOTE_PATH"
    fi
    
    print_status "Repository updated on remote server"
}

# Deploy using rsync (recommended for initial deployment)
deploy_remote_rsync() {
    print_status "Deploying to remote server using rsync..."
    
    # Create remote directory
    ssh "$REMOTE_SERVER" "mkdir -p $REMOTE_PATH"
    
    # Copy project files
    rsync -avz --exclude='.git' \
              --exclude='.gradle' \
              --exclude='.idea' \
              --exclude='build' \
              --exclude='.kotlin' \
              --exclude='*.db-journal' \
              --exclude='.DS_Store' \
              ./ "$REMOTE_SERVER:$REMOTE_PATH/"
    
    print_status "Files copied to remote server"
}

# Setup with Docker
setup_docker() {
    print_status "Setting up backend with Docker..."
    
    ssh "$REMOTE_SERVER" "cd $REMOTE_PATH && \
        sudo systemctl stop bm-backend 2>/dev/null || true && \
        sudo docker-compose down 2>/dev/null || true && \
        sudo docker-compose up -d --build"
    
    print_status "Docker container started"
}

# Build on server
build_on_server() {
    print_status "Building JAR on server..."
    
    ssh "$REMOTE_SERVER" "cd $REMOTE_PATH && \
        chmod +x gradlew && \
        ./gradlew clean shadowJar --no-daemon"
    
    print_status "JAR built on server"
}

# Setup systemd service
setup_systemd() {
    print_status "Setting up systemd service..."
    
    # Create systemd service file
    ssh "$REMOTE_SERVER" "tee /etc/systemd/system/bm-backend.service > /dev/null << 'EOF'
[Unit]
Description=BM Backend Service (Kotlin/Ktor)
After=network.target docling-api.service

[Service]
Type=simple
User=root
WorkingDirectory=/root/BmBackEnd
ExecStart=/usr/bin/java -jar /root/BmBackEnd/build/libs/bm-backend-1.0-all.jar
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

# Environment variables
Environment=\"KTOR_ENV=production\"

[Install]
WantedBy=multi-user.target
EOF"
    
    # Reload systemd and enable service
    ssh "$REMOTE_SERVER" "systemctl daemon-reload && \
        systemctl enable bm-backend && \
        systemctl restart bm-backend"
    
    print_status "Systemd service configured and started"
}

# Update nginx configuration
setup_nginx() {
    print_status "Updating nginx configuration..."
    
    # Create nginx config for backend
    ssh "$REMOTE_SERVER" "tee /etc/nginx/sites-available/bm-backend > /dev/null << 'EOF'
# BM Backend API
server {
    listen 80;
    server_name 217.154.181.175;

    # Backend API endpoints
    location /api/v1/ {
        proxy_pass http://127.0.0.1:8081/api/v1/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 300s;
        proxy_connect_timeout 75s;
    }

    # Health check endpoint
    location /backend/health {
        proxy_pass http://127.0.0.1:8081/health;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
    }

    # Root endpoint
    location /backend/ {
        proxy_pass http://127.0.0.1:8081/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
    }
}
EOF"
    
    # Enable site and reload nginx
    ssh "$REMOTE_SERVER" "ln -sf /etc/nginx/sites-available/bm-backend /etc/nginx/sites-enabled/ && \
        nginx -t && \
        systemctl reload nginx"
    
    print_status "Nginx configured"
}

# Test deployment
test_deployment() {
    print_status "Testing deployment..."
    
    # Wait a bit for service to start
    sleep 5
    
    # Test health endpoint
    if curl -f "http://$DOMAIN:8081/health" > /dev/null 2>&1; then
        print_status "✅ Direct health check passed (port 8081)"
    else
        print_warning "⚠️  Direct health check failed - checking service status..."
    fi
    
    # Check service status
    print_status "Service status:"
    if [ "$USE_DOCKER" = true ]; then
        ssh "$REMOTE_SERVER" "docker ps | grep bm-backend"
        ssh "$REMOTE_SERVER" "docker logs bm-backend --tail 50"
    else
        ssh "$REMOTE_SERVER" "systemctl status bm-backend --no-pager -l"
        ssh "$REMOTE_SERVER" "journalctl -u bm-backend -n 50 --no-pager"
    fi
}

# Main deployment flow
main() {
    print_status "Starting deployment process..."
    
    check_files
    check_server_prerequisites
    
    # Choose deployment method
    if [ -n "$GITHUB_REPO" ]; then
        deploy_remote_git
        build_on_server
    else
        print_status "Using rsync deployment (no GitHub repo configured)"
        deploy_remote_rsync
        build_on_server
    fi
    
    # Setup service
    if [ "$USE_DOCKER" = true ]; then
        setup_docker
    else
        setup_systemd
    fi
    
    setup_nginx
    test_deployment
    
    print_status "🎉 Deployment completed successfully!"
    echo ""
    echo "Access points:"
    echo "  Direct API: http://$DOMAIN:8081"
    echo "  Via nginx: http://$DOMAIN/api/v1/"
    echo "  Health check: http://$DOMAIN:8081/health"
    echo ""
    echo "Service management:"
    if [ "$USE_DOCKER" = true ]; then
        echo "  View logs: ssh $REMOTE_SERVER 'docker logs bm-backend -f'"
        echo "  Restart: ssh $REMOTE_SERVER 'docker-compose restart'"
    else
        echo "  View logs: ssh $REMOTE_SERVER 'journalctl -u bm-backend -f'"
        echo "  Restart: ssh $REMOTE_SERVER 'systemctl restart bm-backend'"
        echo "  Status: ssh $REMOTE_SERVER 'systemctl status bm-backend'"
    fi
}

# Run main function
main "$@"
