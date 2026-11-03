# ==============================================================================
# Stage 1: Build Backend JAR (Java 17 + Maven)
# ==============================================================================
FROM maven:3.9-eclipse-temurin-17 AS backend-builder
WORKDIR /build/backend

# Cache dependencies
COPY backend/pom.xml .
RUN mvn dependency:go-offline -B -q || true

# Compile and package application jar
COPY backend/src ./src
RUN mvn clean package -DskipTests -B -q

# ==============================================================================
# Stage 2: Build Frontend Bundle (Node 20 + Nuxt 3)
# ==============================================================================
FROM node:20-bookworm-slim AS frontend-builder
WORKDIR /build/frontend

# Install dependencies using clean install
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci

# Build production Nuxt bundle
COPY frontend ./
RUN npm run build

# ==============================================================================
# Stage 3: Minimal Unified Runtime Image (Node 20 + Eclipse Temurin 17 JRE)
# ==============================================================================
FROM node:20-bookworm-slim AS runtime

# Copy Temurin JRE 17 from official image
COPY --from=eclipse-temurin:17-jre-jammy /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:$PATH"

WORKDIR /app

# Prepare directories with non-root ownership
RUN mkdir -p /app/backend/target /app/frontend/.output /app/data /app/logs && \
    chown -R node:node /app

# Copy compiled artifacts from builders
COPY --from=backend-builder --chown=node:node /build/backend/target/transaction-compliance-evaluator-0.1.0.jar /app/backend/target/
COPY --from=frontend-builder --chown=node:node /build/frontend/.output /app/frontend/.output
COPY --from=frontend-builder --chown=node:node /build/frontend/package.json /app/frontend/package.json

# Copy scripts and default dataset
COPY --chown=node:node run.sh start-web.sh ./
COPY --chown=node:node data ./data
COPY --chown=node:node docker-entrypoint.sh ./

RUN chmod +x run.sh start-web.sh docker-entrypoint.sh

# Security: Execute as non-root user
USER node

ENV HOST=0.0.0.0
ENV PORT=3000
ENV NODE_ENV=production
ENV APP_LOG_LEVEL=INFO

EXPOSE 3000

# Native Node 20 healthcheck (eliminates curl dependency)
HEALTHCHECK --interval=15s --timeout=5s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:3000').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"

ENTRYPOINT ["./docker-entrypoint.sh"]
CMD ["run"]

