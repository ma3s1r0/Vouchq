import type { NextConfig } from "next";

// Self-hosted constraint (기획서 §7): no external telemetry leaves the box.
// Also set in .env (NEXT_TELEMETRY_DISABLED=1); pinned here too since .env is git-ignored.
process.env.NEXT_TELEMETRY_DISABLED = "1";

/**
 * API proxy target — server-side env var (never exposed to the browser bundle).
 *
 * Cookie/CORS approach: the browser talks only to the console origin.
 * Next.js rewrites /api/:path* → API_PROXY_TARGET/api/:path* at the edge,
 * so the backend's JSESSIONID cookie is set on the console origin and flows
 * back on every subsequent browser request automatically (first-party cookie,
 * no cross-origin issues).
 *
 * Set API_PROXY_TARGET in .env.local or your deployment env.
 * Default for local dev: http://localhost:8080
 */
const API_PROXY_TARGET =
  process.env.API_PROXY_TARGET ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  // Emit a self-contained server (.next/standalone) for a lean production
  // container image — see console/Dockerfile.
  output: "standalone",
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${API_PROXY_TARGET}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
