#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND_DIR="$ROOT_DIR/apps/frontend"
REMOTE_HOST="${REMOTE_HOST:-ktb-frontend}"
REMOTE_DIR="${REMOTE_DIR:-/home/ubuntu/ktb-chat-frontend}"

cd "$FRONTEND_DIR"
# NEXT_PUBLIC_* 값은 Next.js 빌드 시점에 번들에 고정된다.
# 배포 전용 스크립트를 사용해 .env.production 누락을 배포 전에 검출한다.
pnpm run build:production

ssh "$REMOTE_HOST" "mkdir -p '$REMOTE_DIR'"
rsync -az --delete .next/standalone/ "$REMOTE_HOST:$REMOTE_DIR/"
rsync -az --delete .next/static/ "$REMOTE_HOST:$REMOTE_DIR/apps/frontend/.next/static/"
rsync -az --delete public/ "$REMOTE_HOST:$REMOTE_DIR/apps/frontend/public/"
ssh "$REMOTE_HOST" "cd '$REMOTE_DIR' && pm2 delete ktb-chat-frontend >/dev/null 2>&1 || true && pm2 start apps/frontend/server.js --name ktb-chat-frontend && pm2 save"
