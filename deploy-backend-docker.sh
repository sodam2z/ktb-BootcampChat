#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT_DIR/apps/backend"
REMOTE_HOST="${REMOTE_HOST:-ktb-backend}"
REMOTE_APP_DIR="${REMOTE_APP_DIR:-/home/ubuntu/ktb-chat-backend}"
REMOTE_BUILD_DIR="${REMOTE_BUILD_DIR:-/home/ubuntu/ktb-chat-backend-docker/build}"
IMAGE_NAME="${IMAGE_NAME:-ktb-chat-backend}"
CONTAINER_NAME="${CONTAINER_NAME:-ktb-backend}"
IMAGE_TAG="${IMAGE_TAG:-$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)}"
JAVA_TOOL_OPTIONS_VALUE="${JAVA_TOOL_OPTIONS_VALUE:--Xms512m -Xmx1024m}"
JAR_NAME="ktb-chat-backend-0.0.1-SNAPSHOT.jar"

echo "==> Build backend JAR locally"
(cd "$BACKEND_DIR" && make build-jar)

echo "==> Sync runtime Docker build files to $REMOTE_HOST:$REMOTE_BUILD_DIR"
ssh "$REMOTE_HOST" "rm -rf '$REMOTE_BUILD_DIR' && mkdir -p '$REMOTE_BUILD_DIR'"
rsync -az --delete \
  "$BACKEND_DIR/Dockerfile.runtime" \
  "$BACKEND_DIR/target/$JAR_NAME" \
  "$REMOTE_HOST:$REMOTE_BUILD_DIR/"

echo "==> Build Docker image on $REMOTE_HOST: $IMAGE_NAME:$IMAGE_TAG"
ssh "$REMOTE_HOST" "cd '$REMOTE_BUILD_DIR' && sudo docker build -f Dockerfile.runtime -t '$IMAGE_NAME:$IMAGE_TAG' -t '$IMAGE_NAME:latest' ."

echo "==> Replace systemd backend with Docker container"
ssh "$REMOTE_HOST" "
set -euo pipefail

APP_DIR='$REMOTE_APP_DIR'
CONTAINER='$CONTAINER_NAME'
IMAGE='$IMAGE_NAME:$IMAGE_TAG'
JAVA_TOOL_OPTIONS_VALUE='$JAVA_TOOL_OPTIONS_VALUE'

if [ ! -f \"\$APP_DIR/.env\" ]; then
  echo \"Missing \$APP_DIR/.env\" >&2
  exit 1
fi

mkdir -p \"\$APP_DIR/uploads\"
PREVIOUS_SYSTEMD_STATE=\$(systemctl is-active ktb-backend || true)

rollback() {
  echo \"Docker backend failed; rolling back to systemd ktb-backend\" >&2
  sudo docker rm -f \"\$CONTAINER\" >/dev/null 2>&1 || true
  if [ \"\$PREVIOUS_SYSTEMD_STATE\" = \"active\" ]; then
    sudo systemctl start ktb-backend || true
  fi
}

sudo docker rm -f \"\$CONTAINER\" >/dev/null 2>&1 || true
sudo systemctl stop ktb-backend || true

sudo docker run -d \
  --name \"\$CONTAINER\" \
  --restart unless-stopped \
  --env-file \"\$APP_DIR/.env\" \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e JAVA_TOOL_OPTIONS=\"\$JAVA_TOOL_OPTIONS_VALUE\" \
  -v \"\$APP_DIR/uploads:/app/uploads\" \
  -p 5001:5001 \
  -p 5002:5002 \
  \"\$IMAGE\"

for attempt in \$(seq 1 40); do
  if curl -fsS http://localhost:5001/api/health >/dev/null 2>&1; then
    sudo systemctl disable ktb-backend >/dev/null 2>&1 || true
    echo \"Docker backend is healthy\"
    sudo docker ps --filter \"name=\$CONTAINER\"
    exit 0
  fi
  if ! sudo docker ps --filter \"name=\$CONTAINER\" --filter status=running --format '{{.Names}}' | grep -qx \"\$CONTAINER\"; then
    sudo docker logs --tail 120 \"\$CONTAINER\" >&2 || true
    rollback
    exit 1
  fi
  sleep 3
done

sudo docker logs --tail 120 \"\$CONTAINER\" >&2 || true
rollback
exit 1
"

echo "==> Verify public ALB routes"
curl -fsS https://chat.goorm-ktb-012.goorm.team/api/health
echo
curl -fsS 'https://chat.goorm-ktb-012.goorm.team/socket.io/?EIO=4&transport=polling' >/dev/null
echo "Docker backend deployment completed."
