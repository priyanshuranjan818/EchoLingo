# EchoLingo — Deployment Guide

> This guide covers deploying the Spring Boot server to AWS EC2 using Docker,
> and building the Android app in Android Studio.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [EC2 Instance Setup](#1-ec2-instance-setup)
3. [SSH into EC2](#2-ssh-into-ec2)
4. [Install Docker on EC2](#3-install-docker-on-ec2)
5. [Upload Code to EC2](#4-upload-code-to-ec2)
6. [Configure Environment](#5-configure-environment)
7. [Build & Start the Server](#6-build--start-the-server)
8. [Verify the Server](#7-verify-the-server)
9. [Auto-Restart on Reboot](#8-auto-restart-on-reboot)
10. [Android Studio Build](#9-android-studio-build)
11. [First-Time App Setup](#10-first-time-app-setup)
12. [Updating the Server](#11-updating-the-server)
13. [Useful Commands](#12-useful-commands)
14. [Cost Estimate](#13-cost-estimate)
15. [Troubleshooting](#14-troubleshooting)

---

## Prerequisites

| Tool | Where to get | Required for |
|---|---|---|
| AWS Account | [aws.amazon.com](https://aws.amazon.com) | EC2 server |
| Android Studio Ladybug+ | [developer.android.com/studio](https://developer.android.com/studio) | Android app |
| Groq API Key (free) | [console.groq.com/keys](https://console.groq.com/keys) | Shadowing mode (BYOK) |

---

## 1. EC2 Instance Setup

1. Go to **[console.aws.amazon.com](https://console.aws.amazon.com)** → **EC2** → **Launch Instance**

2. Configure:

   | Setting | Recommended Value |
   |---|---|
   | **Name** | `echolingo-server` |
   | **AMI** | `Ubuntu Server 24.04 LTS` |
   | **Instance type** | `t3.small` (2 vCPU, 2 GB RAM) |
   | **Key pair** | Create new → name `echolingo-key` → download `.pem` → **save safely** |
   | **Storage** | 20 GB gp3 |

   > **Free tier users:** Use `t2.micro` instead of `t3.small`.
   > yt-dlp will be slower but functional.

3. Under **Network settings** → **Edit** → add these **Inbound Rules**:

   | Type | Protocol | Port | Source | Purpose |
   |---|---|---|---|---|
   | SSH | TCP | 22 | My IP | Connect from your machine |
   | Custom TCP | TCP | 3001 | 0.0.0.0/0 | Android app API calls |

4. Click **Launch Instance**. Wait ~60 seconds.

5. Go to **EC2 → Instances** → copy the **Public IPv4 address**.
   Example: `54.210.12.34` — you'll use this throughout.

---

## 2. SSH into EC2

**Windows (PowerShell):**

```powershell
# Move the key file to your .ssh folder
Move-Item "$env:USERPROFILE\Downloads\echolingo-key.pem" "$env:USERPROFILE\.ssh\"

# Fix permissions — SSH requires this
icacls "$env:USERPROFILE\.ssh\echolingo-key.pem" /inheritance:r /grant:r "$env:USERNAME:R"

# Connect (replace with your actual EC2 IP)
ssh -i "$env:USERPROFILE\.ssh\echolingo-key.pem" ubuntu@54.210.12.34
```

**Mac / Linux:**

```bash
chmod 400 ~/Downloads/echolingo-key.pem
ssh -i ~/Downloads/echolingo-key.pem ubuntu@54.210.12.34
```

You should see the Ubuntu shell prompt:
```
ubuntu@ip-172-31-xx-xx:~$
```

---

## 3. Install Docker on EC2

Run these commands **inside the SSH session**:

```bash
# Update system packages
sudo apt-get update && sudo apt-get upgrade -y

# Install Docker (official one-liner)
curl -fsSL https://get.docker.com | sudo sh

# Add ubuntu user to docker group (avoids needing sudo for docker commands)
sudo usermod -aG docker ubuntu

# Install Docker Compose plugin
sudo apt-get install -y docker-compose-plugin

# Apply group change without re-logging in
newgrp docker

# Verify both are installed
docker --version
docker compose version
```

Expected output:
```
Docker version 27.x.x, build ...
Docker Compose version v2.x.x
```

---

## 4. Upload Code to EC2

**Option A — Git (recommended):**

```bash
# On EC2
sudo apt-get install -y git
git clone https://github.com/YOUR_USERNAME/EchoLingo.git
cd EchoLingo
```

**Option B — SCP from Windows (no GitHub):**

```powershell
# Run on your Windows machine
scp -i "$env:USERPROFILE\.ssh\echolingo-key.pem" -r `
  "C:\Users\priya\Desktop\Haxx" `
  ubuntu@54.210.12.34:~/EchoLingo
```

```bash
# Then on EC2
cd ~/EchoLingo
```

---

## 5. Configure Environment

```bash
# From ~/EchoLingo
cp .env.example .env
nano .env
```

Set your Groq key (paste your key from [console.groq.com/keys](https://console.groq.com/keys)):

```env
# Leave blank if users will use BYOK (enter key in the Android app Settings)
GROQ_API_KEY=gsk_your_key_here
```

Save: `Ctrl+O` → Enter → `Ctrl+X`

**All available options:**

```env
# Required for server-side shadowing fallback (or leave blank for BYOK-only)
GROQ_API_KEY=gsk_...

# Optional — only needed if you want proxy rotation for yt-dlp
# ECHOLINGO_YTDLP_PROXY_LIST=http://proxy1:8080,http://proxy2:8080

# Optional — DeepL instead of Google Translate (better quality)
# ECHOLINGO_DEEPL_KEY=your_deepl_key
```

---

## 6. Build & Start the Server

```bash
# IMPORTANT: run from the repo root (~/EchoLingo), NOT from server/
cd ~/EchoLingo

# Build Docker image + start container in background
# First run takes ~5-8 minutes (downloads Maven deps + yt-dlp)
docker compose up --build -d
```

Watch the logs until the server is ready:

```bash
docker compose logs -f
```

Wait for this line:
```
echolingo-server  | Started EchoLingoApplication on port 3001
```

Press `Ctrl+C` to exit logs (server keeps running in background).

---

## 7. Verify the Server

```bash
# Health check (from EC2 shell)
curl http://localhost:3001/api/health
# Expected: {"status":"ok"}

# Test a real import (from EC2 or your Windows machine)
curl -X POST http://54.210.12.34:3001/api/import \
  -H "Content-Type: application/json" \
  -d '{"url":"dQw4w9WgXcQ"}'
```

A successful response looks like:
```json
{
  "videoId": "dQw4w9WgXcQ",
  "title": "...",
  "hasDe": true,
  "hasEn": true,
  "scores": { "overallScore": 87 },
  "cached": false
}
```

---

## 8. Auto-Restart on Reboot

The `restart: unless-stopped` in `docker-compose.yml` handles container restarts.
To make Docker itself start on EC2 reboot:

```bash
sudo systemctl enable docker
```

Test it:

```bash
sudo reboot
# Wait 30 seconds, then SSH back in
docker ps
# Should show echolingo-server running
```

---

## 9. Android Studio Build

### 9.1 — Install Android Studio

Download **Android Studio Ladybug (2024.2.x)** or later:
**[developer.android.com/studio](https://developer.android.com/studio)**

Install with default settings. Let the setup wizard download the Android SDK.

---

### 9.2 — Open the Project

1. Launch Android Studio
2. Click **Open** (not "New Project")
3. Navigate to `C:\Users\priya\Desktop\Haxx\android`
4. Click **OK**

> ⚠️ Open the **`android/`** subfolder, not the root `Haxx/` folder.
> Android Studio must see `android/settings.gradle.kts` at the top level.

Wait for **Gradle sync** to complete (~2-4 minutes first time).

---

### 9.3 — (Optional) Set Default Server URL

Open:
```
android/app/src/main/java/com/echolingo/app/data/preferences/SettingsRepository.kt
```

Change the default:
```kotlin
// Before
val serverBaseUrl: String = "http://10.0.2.2:3001/",

// After (your EC2 IP)
val serverBaseUrl: String = "http://54.210.12.34:3001/",
```

> Alternatively, skip this and set it in-app via **⚙ Settings → Backend Server URL**.

---

### 9.4 — Run on Emulator

1. **Device Manager** (right sidebar) → **+** → **Create Virtual Device**
2. Choose **Pixel 8** → Next
3. System Image: **UpsideDownCake (API 34)** → Download → Next → Finish
4. Click **▶** to start the emulator
5. Press the green **▶ Run** button in Android Studio

> When using emulator, set server URL to `http://10.0.2.2:3001/`
> (this maps to your PC's localhost from inside the emulator).

---

### 9.5 — Run on Physical Phone

1. **Settings → About Phone** → tap **Build Number 7 times** → Developer Options unlocked
2. **Settings → Developer Options** → enable **USB Debugging**
3. Connect phone via USB → allow debugging prompt on the phone
4. Phone appears in Android Studio device dropdown
5. Press **▶ Run**

> Set server URL to `http://YOUR_PC_LAN_IP:3001/` (run `ipconfig` to find it)
> or use your EC2 URL directly.

---

### 9.6 — Build a Release APK

1. **Build** menu → **Generate Signed App Bundle / APK** → **APK** → Next

2. **Create new keystore** (first time only):

   | Field | Value |
   |---|---|
   | Key store path | `C:\Users\priya\echolingo-release.jks` |
   | Password | Choose strong, save securely |
   | Key alias | `echolingo` |
   | Validity | 25 years |
   | Certificate name | Your name |

3. Select **release** build variant → Click **Finish**

4. APK is saved to:
   ```
   android\app\build\outputs\apk\release\app-release.apk
   ```

5. Transfer to your phone (USB / Google Drive / any method)

6. On the phone: **Settings → Install Unknown Apps** → enable for your file manager → install the APK

---

## 10. First-Time App Setup

After installing and opening EchoLingo:

1. Tap **⚙ Settings** (top-right on Home screen)
2. Set **Backend Server URL** → `http://54.210.12.34:3001/`
3. Set **Groq API Key** → paste your `gsk_...` key → tap **Save**
   - Get a free key at **[console.groq.com/keys](https://console.groq.com/keys)**
4. Close settings

5. Paste a German YouTube URL → tap **Import Video** (10-30 seconds)

6. **Mode Select** appears → choose:
   - 🎬 **Watch** — dual subtitles, plays continuously
   - 🎤 **Shadow** — pauses after each sentence, you repeat it

7. ✅ You're ready!

---

## 11. Updating the Server

After changing code on your machine:

**If using Git:**
```bash
# On EC2
cd ~/EchoLingo
git pull
docker compose up --build -d
```

**If using SCP:**
```powershell
# On Windows — re-upload
scp -i "$env:USERPROFILE\.ssh\echolingo-key.pem" -r `
  "C:\Users\priya\Desktop\Haxx\server" `
  ubuntu@54.210.12.34:~/EchoLingo/
```
```bash
# On EC2
cd ~/EchoLingo
docker compose up --build -d
```

---

## 12. Useful Commands

```bash
# ── Container management ──────────────────────────────────────────
docker ps                                       # list running containers
docker compose up -d                            # start (image already built)
docker compose up --build -d                    # rebuild + start
docker compose down                             # stop and remove containers
docker compose restart                          # restart server

# ── Logs ─────────────────────────────────────────────────────────
docker compose logs -f                          # live logs (Ctrl+C to exit)
docker compose logs --tail=100                  # last 100 lines

# ── Cache management ─────────────────────────────────────────────
docker exec echolingo-server ls /app/cache      # list cached video IDs
docker volume ls                                # list all volumes
docker volume rm echolingo_echolingo-cache       # delete all subtitle caches

# ── Debugging ────────────────────────────────────────────────────
docker exec -it echolingo-server bash           # shell inside container
docker exec echolingo-server yt-dlp --version   # verify yt-dlp works

# ── Server resource usage ─────────────────────────────────────────
docker stats echolingo-server                   # live CPU/RAM usage
```

---

## 13. Cost Estimate

| Resource | Free Tier | After Free Tier |
|---|---|---|
| t2.micro EC2 (750 hrs/month) | **$0** (12 months) | ~$9/month |
| t3.small EC2 | Not included | ~$15/month |
| 20 GB gp3 storage | 30 GB free | ~$1.60/month |
| Data transfer (first 1 GB) | Free | ~$0.09/GB |
| **Total (free tier)** | **$0/month** | — |
| **Total (t3.small)** | — | **~$17/month** |

> Get a free Groq key (no credit card): [console.groq.com](https://console.groq.com)

---

## 14. Troubleshooting

### Server won't start

```bash
docker compose logs echolingo-server | tail -50
```

Common causes:
- Port 3001 already in use: `sudo lsof -i :3001`
- Out of disk space: `df -h`
- Java OOM: upgrade to t3.small or increase swap

### `yt-dlp: command not found`

```bash
docker exec echolingo-server yt-dlp --version
# If this fails, rebuild: docker compose up --build -d
```

### App can't connect to server

1. Check security group allows TCP 3001 from 0.0.0.0/0
2. Verify URL in app Settings ends with `/` → `http://54.210.12.34:3001/`
3. Test from phone browser: `http://54.210.12.34:3001/api/health`

### Shadowing returns "No Groq API key"

- Open app → **⚙ Settings** → **Groq API Key** → paste key → **Save**
- Or set `GROQ_API_KEY` in the server `.env` and `docker compose up --build -d`

### Import takes too long / times out

- YouTube may be rate-limiting — wait a few minutes and retry
- Add a proxy: set `ECHOLINGO_YTDLP_PROXY_LIST` in `.env`
- Check yt-dlp is up to date: `docker exec echolingo-server yt-dlp -U`

### Gradle sync fails in Android Studio

- Check you opened `android/` subfolder not `Haxx/`
- File → Invalidate Caches → Restart
- Check Java 17+ is available: **File → Project Structure → SDK**

---

*Last updated: May 2026*
