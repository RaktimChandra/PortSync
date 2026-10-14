// Captures dashboard screenshots from a running stack (used to produce docs/screenshots).
// Usage (from the repo root, with the stack running):
//   npm install --no-save playwright && npx playwright install chromium
//   node scripts/screenshots.mjs [baseUrl] [outDir] [grafanaUrl]
import { chromium } from 'playwright';

const base = process.argv[2] ?? 'http://localhost:3000';
const out = process.argv[3] ?? 'docs/screenshots';
const grafana = process.argv[4] ?? 'http://localhost:3001';
const pages = [
  ['overview', '/'],
  ['berths', '/berths'],
  ['yard', '/yard?block=D'],
  ['container', null],
  ['alerts', '/alerts'],
  ['events', '/events'],
];

const browser = await chromium.launch();
for (const scheme of ['dark', 'light']) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: scheme, deviceScaleFactor: 1 });
  const page = await context.newPage();
  for (const [name, path] of pages) {
    if (name === 'container') {
      // Pick a real container from the yard grid.
      await page.goto(`${base}/yard?block=A`, { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('.stack');
      await page.locator('.stack[data-height="3"], .stack[data-height="2"], .stack[data-height="1"]').first().click();
      const link = page.locator('.slot-list a').first();
      const number = (await link.textContent())?.trim();
      await page.goto(`${base}/containers?number=${number}`, { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('.timeline', { timeout: 10_000 });
    } else {
      // The live stream keeps a connection open, so "network idle" never happens; wait for content instead.
      await page.goto(`${base}${path}`, { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('h1');
      if (name === 'yard') {
        await page.waitForSelector('.stack');
        await page.locator('.stack[data-height="4"], .stack[data-height="3"]').first().click();
      }
    }
    await page.waitForTimeout(2500);
    const file = `${out}/${name}${scheme === 'light' ? '-light' : ''}.png`;
    await page.screenshot({ path: file, fullPage: true });
    console.log('saved', file);
  }
  await context.close();
}

// The provisioned Grafana dashboard (anonymous viewer access is enabled in Compose). Best effort:
// Grafana's markup changes between versions, so wait for the page to settle instead of a selector.
const grafanaContext = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme: 'dark' });
try {
  const grafanaPage = await grafanaContext.newPage();
  await grafanaPage.goto(`${grafana}/d/portsync-ops?orgId=1&from=now-15m&to=now&kiosk`, { waitUntil: 'load', timeout: 30_000 });
  await grafanaPage.waitForTimeout(12_000);
  await grafanaPage.screenshot({ path: `${out}/grafana.png`, fullPage: true });
  console.log('saved', `${out}/grafana.png`);
} catch (e) {
  console.warn('Grafana screenshot skipped:', e.message);
}
await grafanaContext.close();
await browser.close();
