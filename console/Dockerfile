# Console (Next.js) production image — standalone output, served by Node.
# Works with both `docker build` and `podman build`.
#
# IMPORTANT: Next evaluates next.config rewrites() at BUILD time, so the API
# proxy target is baked into the image. In compose the backend service is always
# reachable as `app`, hence the default below. Override for a different topology:
#   docker build --build-arg API_PROXY_TARGET=http://backend:8080 ./console

# Stage 1: install dependencies (cached unless lockfile changes).
FROM docker.io/library/node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# Stage 2: production build (Next standalone output).
FROM docker.io/library/node:20-alpine AS build
WORKDIR /app
ARG API_PROXY_TARGET=http://app:8080
ENV API_PROXY_TARGET=${API_PROXY_TARGET}
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# Stage 3: minimal runtime — only the standalone server + static assets.
FROM docker.io/library/node:20-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0
# Run as a non-root user.
RUN addgroup -S -g 1001 nodejs && adduser -S -u 1001 -G nodejs nextjs
COPY --from=build /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
USER 1001
EXPOSE 3000
CMD ["node", "server.js"]
