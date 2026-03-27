# =============================================================================
# SyncFlow API Server - Docker Image
# Multi-stage build: compile TypeScript, then run lean production image
# =============================================================================

# ---------- Stage 1: Build ----------
FROM node:20-alpine AS builder

WORKDIR /app

# Install dependencies first (layer cache)
COPY package*.json ./
RUN npm ci

# Copy source and compile
COPY tsconfig.json ./
COPY src/ ./src/
RUN npm run build

# ---------- Stage 2: Production ----------
FROM node:20-alpine

# postgresql-client: needed by entrypoint to run psql for migrations
# curl: needed by HEALTHCHECK
RUN apk add --no-cache postgresql-client curl bash

# Run as non-root
RUN addgroup -S syncflow && adduser -S syncflow -G syncflow

WORKDIR /app

# Install production dependencies only
COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force

# Copy compiled output and assets
COPY --from=builder /app/dist ./dist
COPY schema.sql ./
COPY migrations/ ./migrations/
COPY docker-entrypoint.sh ./
RUN chmod +x docker-entrypoint.sh

# Create directories the app may need at runtime
RUN mkdir -p downloads logs && chown -R syncflow:syncflow /app

USER syncflow

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:3000/health || exit 1

ENTRYPOINT ["./docker-entrypoint.sh"]
CMD ["node", "dist/index.js"]
