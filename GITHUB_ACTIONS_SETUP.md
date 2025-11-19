# GitHub Actions Setup Guide

This guide explains how to set up automated deployment using GitHub Actions.

## 🔑 Prerequisites

You need to add your SSH private key to GitHub Secrets for automated deployment.

## 📝 Setup Steps

### 1. Generate SSH Key (if you don't have one)

On your local machine:

```bash
# Generate a new SSH key pair
ssh-keygen -t ed25519 -C "github-actions@bmbackend" -f ~/.ssh/bmbackend_deploy

# This creates two files:
# - ~/.ssh/bmbackend_deploy (private key)
# - ~/.ssh/bmbackend_deploy.pub (public key)
```

### 2. Add Public Key to Server

Copy the public key to your server:

```bash
# Copy public key content
cat ~/.ssh/bmbackend_deploy.pub

# Add it to server's authorized_keys
ssh root@217.154.181.175 "mkdir -p ~/.ssh && echo 'YOUR_PUBLIC_KEY_HERE' >> ~/.ssh/authorized_keys"

# Or use ssh-copy-id
ssh-copy-id -i ~/.ssh/bmbackend_deploy.pub root@217.154.181.175
```

### 3. Add Private Key to GitHub Secrets

1. Go to your GitHub repository: https://github.com/VictorGeoSoftware/BmBackEnd
2. Click on **Settings** → **Secrets and variables** → **Actions**
3. Click **New repository secret**
4. Name: `SSH_PRIVATE_KEY`
5. Value: Copy the entire content of your private key:
   ```bash
   cat ~/.ssh/bmbackend_deploy
   ```
6. Click **Add secret**

### 4. Test the Workflow

#### Option A: Push to Main Branch

```bash
git add .
git commit -m "Setup deployment workflow"
git push origin main
```

The workflow will automatically trigger on push to `main`.

#### Option B: Manual Trigger

1. Go to **Actions** tab in your GitHub repository
2. Select **Deploy to Production** workflow
3. Click **Run workflow**
4. Select `main` branch
5. Click **Run workflow**

## 🔍 Monitoring Deployment

### View Workflow Logs

1. Go to **Actions** tab in GitHub
2. Click on the latest workflow run
3. Click on **Build and Deploy to Server** job
4. Expand steps to see detailed logs

### Check Server Status

```bash
# SSH into server
ssh root@217.154.181.175

# Check service status
systemctl status bm-backend

# View logs
journalctl -u bm-backend -f
```

## 🎯 Workflow Details

The GitHub Actions workflow (`.github/workflows/deploy.yml`) does the following:

1. **Checkout code** - Gets the latest code from the repository
2. **Set up JDK 17** - Installs Java for building
3. **Build with Gradle** - Creates the JAR file using `shadowJar`
4. **Setup SSH** - Configures SSH access to the server
5. **Deploy to Server** - Copies JAR and restarts the service
6. **Health Check** - Verifies the deployment was successful

## 🔧 Customization

### Change Deployment Branch

Edit `.github/workflows/deploy.yml`:

```yaml
on:
  push:
    branches:
      - main        # Change to your branch
      - production  # Add more branches
```

### Add Environment Variables

Add secrets in GitHub:

1. Go to **Settings** → **Secrets and variables** → **Actions**
2. Add secrets like:
   - `DATABASE_URL`
   - `API_KEY`
   - etc.

Use them in the workflow:

```yaml
- name: Deploy to Server
  env:
    DATABASE_URL: ${{ secrets.DATABASE_URL }}
  run: |
    ssh root@217.154.181.175 "echo 'DATABASE_URL=$DATABASE_URL' >> /root/BmBackEnd/.env"
```

### Add Slack/Discord Notifications

Add a notification step:

```yaml
- name: Notify Slack
  if: always()
  uses: 8398a7/action-slack@v3
  with:
    status: ${{ job.status }}
    webhook_url: ${{ secrets.SLACK_WEBHOOK }}
```

## 🆘 Troubleshooting

### SSH Connection Failed

**Error**: `Permission denied (publickey)`

**Solution**:
1. Verify the private key is correctly added to GitHub Secrets
2. Ensure the public key is in `/root/.ssh/authorized_keys` on the server
3. Check SSH key permissions on server: `chmod 600 ~/.ssh/authorized_keys`

### Build Failed

**Error**: `Task 'shadowJar' not found`

**Solution**:
1. Ensure `build.gradle.kts` has the Shadow plugin configured
2. Verify Gradle wrapper is committed: `git add gradlew gradlew.bat gradle/`

### Service Restart Failed

**Error**: `Failed to restart bm-backend.service`

**Solution**:
1. Check if service file exists: `ssh root@217.154.181.175 "ls -l /etc/systemd/system/bm-backend.service"`
2. Run initial deployment manually first: `./deploy-remote.sh`
3. Check service logs: `ssh root@217.154.181.175 "journalctl -u bm-backend -n 50"`

### Health Check Failed

**Error**: `curl: (7) Failed to connect to 217.154.181.175 port 8081`

**Solution**:
1. Check if service is running: `ssh root@217.154.181.175 "systemctl status bm-backend"`
2. Check application logs: `ssh root@217.154.181.175 "journalctl -u bm-backend -n 100"`
3. Verify port 8081 is open: `ssh root@217.154.181.175 "netstat -tlnp | grep 8081"`

## 🔒 Security Best Practices

1. **Use Deploy Keys**: Instead of personal SSH keys, use GitHub Deploy Keys
2. **Limit SSH Key Access**: Create a dedicated user on the server for deployments
3. **Rotate Keys Regularly**: Change SSH keys periodically
4. **Use Environment Variables**: Never commit secrets to the repository
5. **Enable Branch Protection**: Require pull request reviews before merging to `main`

## 📊 Deployment Status Badge

Add this to your README.md to show deployment status:

```markdown
![Deploy Status](https://github.com/VictorGeoSoftware/BmBackEnd/actions/workflows/deploy.yml/badge.svg)
```

## 🎉 Next Steps

After setting up GitHub Actions:

1. ✅ Test the workflow with a small change
2. ✅ Monitor the first few deployments
3. ✅ Set up notifications for deployment failures
4. ✅ Consider adding a staging environment
5. ✅ Add automated tests before deployment

---

**Need Help?** Check the [GitHub Actions documentation](https://docs.github.com/en/actions) or review the workflow logs in the Actions tab.
