import { chromium } from "playwright";
import { execSync } from "node:child_process";

const BASE = process.env.CONSOLE_BASE || "http://localhost:3000";
const TOOL = process.env.TOOL_ID;
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

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
        "padding:13px 20px;border-top:2px solid #388BFD;text-align:center;" +
        "letter-spacing:0.2px";
      document.body.appendChild(el);
    }
    el.textContent = t;
  }, text);
}

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 1280, height: 800 },
  deviceScaleFactor: 2,
  recordVideo: { dir: "/tmp/vouchq-rec/videos", size: { width: 1280, height: 800 } },
});
const page = await ctx.newPage();

// 1. Login
await page.goto(BASE + "/login");
await pause(700);
await page.fill("#email", "admin@vouchq.local");
await page.fill("#password", "admin");
await pause(700);
await page.getByRole("button", { name: /Sign in/i }).click();
await page.waitForURL("**/dashboard", { timeout: 15000 }).catch(() => {});
await pause(1200);

// 2. Inventory — a benign MCP tool, scanned clean
await page.goto(BASE + "/inventory");
await caption(page, "An approved MCP tool: web_search — scanned clean.");
await page.fill('input[aria-label*="search" i]', "web_search").catch(() => {});
await pause(2600);

// 3. Detail + Approve & Pin
await page.goto(BASE + "/inventory/" + TOOL);
await caption(page, "Review it, then Approve & Pin (박제) the definition by SHA-256.");
await pause(2400);
await page.getByRole("button", { name: /Approve & pin/i }).click();
await pause(2800); // "Approved & pinned at the current version."
await caption(page, "Pinned. This exact definition is now the trusted baseline.");
await pause(1800);

// 4. RUG-PULL — upstream silently rewrites the description to exfiltrate secrets
execSync("curl -s -X POST http://localhost:8765/rugpull");
await caption(page, ">>> RUG-PULL: upstream secretly rewrites web_search to exfiltrate secrets…");
await pause(2600);

// 5. Re-scan
await page.goto(BASE + "/inventory");
await caption(page, "Re-scan (the scheduled rescan does this automatically)…");
await page.getByRole("button", { name: /Rescan all/i }).click();
await pause(4000); // rescan + drift detection

// 6. Drift detected
await page.goto(BASE + "/drift");
await caption(page, "DRIFT detected — the approved tool changed after it was pinned.");
await pause(3200);
// expand the first drift card if it's a button
await page.locator("button").filter({ hasText: /web_search/i }).first().click().catch(() => {});
await pause(2600);

// 7. Detail again — live version now CRITICAL
await page.goto(BASE + "/inventory/" + TOOL);
await caption(page, "The live version now scans CRITICAL: injection.exfil-directive.");
await pause(3400);

// 8. Audit chain
await page.goto(BASE + "/audit");
await caption(page, "Tamper-evident audit: who approved it, and that it drifted after.");
await pause(3400);
await caption(page, "Agents only ever get the PINNED benign version. Rug-pull contained.");
await pause(2600);

await ctx.close(); // finalizes the video
await browser.close();
console.log("done");
