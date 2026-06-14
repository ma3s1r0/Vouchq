import { chromium } from "playwright";
import { execSync } from "node:child_process";

const BASE = process.env.CONSOLE_BASE || "http://localhost:3000";
const TOOL = process.env.TOOL_ID;
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

// Caption is appended to <body> (outside Next's React root) so it survives
// client-side navigations — we just update its text between steps.
async function caption(page, text) {
  await page.evaluate((t) => {
    let el = document.getElementById("__cap");
    if (!el) {
      el = document.createElement("div");
      el.id = "__cap";
      el.style.cssText =
        "position:fixed;left:0;right:0;bottom:0;z-index:99999;" +
        "background:rgba(11,14,20,0.94);color:#E6EDF3;" +
        "font:600 16px/1.5 ui-monospace,SFMono-Regular,monospace;" +
        "padding:13px 20px;border-top:2px solid #388BFD;text-align:center;letter-spacing:0.2px";
      document.body.appendChild(el);
    }
    el.textContent = t;
  }, text);
}
const nav = (page, name) => page.getByRole("link", { name }).first().click();

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 1280, height: 800 },
  deviceScaleFactor: 2,
  reducedMotion: "reduce", // no drift-pulse animation in the capture
  recordVideo: { dir: "/tmp/vouchq-rec/videos", size: { width: 1280, height: 800 } },
});
const page = await ctx.newPage();

// Login (the only full page load; everything after is client-side nav → no reload flash)
await page.goto(BASE + "/login");
await page.fill("#email", "admin@vouchq.local");
await page.fill("#password", "admin");
await pause(600);
await page.getByRole("button", { name: /Sign in/i }).click();
await page.waitForURL("**/dashboard", { timeout: 15000 }).catch(() => {});
await page.getByText(/Organization overview|조직 현황/i).first().waitFor({ timeout: 10000 }).catch(() => {});
await pause(1200);

// Inventory (client nav) — a benign MCP tool, scanned clean
await nav(page, /Inventory/i);
await page.getByRole("link", { name: /web_search/i }).first().waitFor({ timeout: 10000 });
await caption(page, "An approved MCP tool: web_search — scanned clean.");
await pause(2400);

// Detail (client nav via the row link) + Approve & Pin
await page.getByRole("link", { name: /web_search/i }).first().click();
await page.getByRole("button", { name: /Approve & pin/i }).waitFor({ timeout: 10000 });
await caption(page, "Review it, then Approve & Pin (박제) the definition by SHA-256.");
await pause(2200);
await page.getByRole("button", { name: /Approve & pin/i }).click();
await page.getByText(/Approved & pinned/i).waitFor({ timeout: 10000 }).catch(() => {});
await caption(page, "Pinned. This exact definition is now the trusted baseline.");
await pause(2400);

// RUG-PULL — upstream silently rewrites the description to exfiltrate secrets
execSync("curl -s -X POST http://localhost:8765/rugpull");
await caption(page, ">>> RUG-PULL: upstream secretly rewrites web_search to exfiltrate secrets…");
await pause(2600);

// Re-scan (client nav back to inventory, click Rescan all)
await nav(page, /Inventory/i);
await page.getByRole("button", { name: /Rescan all/i }).waitFor({ timeout: 10000 });
await caption(page, "Re-scan (the scheduled rescan does this automatically)…");
await page.getByRole("button", { name: /Rescan all/i }).click();
await pause(4200); // rescan + drift detection

// Drift (client nav)
await nav(page, /Drift/i);
await caption(page, "DRIFT detected — the approved tool changed after it was pinned.");
await pause(3400);

// Back to the tool — live version now CRITICAL (client nav → row)
await nav(page, /Inventory/i);
await page.getByRole("link", { name: /web_search/i }).first().click();
await caption(page, "The live version now scans CRITICAL: injection.exfil-directive.");
await pause(3400);

// Audit chain (client nav)
await nav(page, /Audit log/i);
await caption(page, "Tamper-evident audit: who approved it, and that it drifted after.");
await pause(3200);
await caption(page, "Agents only ever get the PINNED benign version. Rug-pull contained.");
await pause(2600);

await ctx.close();
await browser.close();
console.log("done");
