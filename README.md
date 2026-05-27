# EchoLingo

EchoLingo is an Android language-learning app backed by a Spring Boot API. The app imports a YouTube URL, loads source and translated subtitle cues from the backend, and plays the video with a draggable dual-subtitle overlay.

## Current Build Stage

This repo is scaffolded for stage-by-stage development:

- `server/` contains the Spring Boot backend and Dockerfile for EC2 deployment.
- `android/` contains the Kotlin/Compose Android app.
- The transcript sidebar is intentionally not part of the plan.
- The player includes dual subtitle toggles and draggable subtitle position.

The backend endpoints are wired with scaffold data first. The next backend stage is replacing the scaffold import data with the real `yt-dlp`, VTT parsing, translation, and file-cache pipeline.

## Backend: Local Docker

From the repo root:

```bash
docker build -f server/Dockerfile -t echolingo-server .
docker run --rm -p 3001:3001 echolingo-server
```

Health check:

```bash
curl http://localhost:3001/health
```

## Backend: EC2 Deployment

On the EC2 instance:

```bash
sudo yum update -y
sudo yum install -y docker git
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user
```

After reconnecting to SSH:

```bash
git clone <your-repo-url> echolingo
cd echolingo
docker build -f server/Dockerfile -t echolingo-server .
docker run -d \
  --name echolingo-server \
  --restart unless-stopped \
  -p 3001:3001 \
  -e PORT=3001 \
  -e ECHOLINGO_CACHE_DIR=/app/cache \
  -v echolingo-cache:/app/cache \
  echolingo-server
```

Open port `3001` in the EC2 security group, or put Nginx/Caddy in front of it with HTTPS.

## Android

Open `android/` in Android Studio.

For the emulator, use this backend URL in the app:

```text
http://10.0.2.2:3001/
```

For a physical device, use the EC2 URL or your machine LAN IP:

```text
http://<your-ec2-public-ip>:3001/
```

## API Shape

```http
POST /api/import
GET /api/meta/{videoId}
GET /api/subtitles/{videoId}/{lang}
GET /api/video/{videoId}/stream
```

`/api/video/{videoId}/stream` resolves a playable URL with `yt-dlp` and returns a `302`. Real subtitle extraction and caching are the next backend stage.
