// NASQO public-only browser audit, 2026-11-11.
// No credentials, form submissions, customer APIs, or production mutations.
// Browser locale does NOT imply app locale: record observed document direction.
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const root = 'https://erp.indexesstore.com';
const dir = path.join(process.cwd(), 'qa-artifacts');
fs.mkdirSync(dir, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true });
  const results = [];
  try {
    for (const language of ['ar', 'en']) {
      const context = await browser.newContext({
        locale: language === 'ar' ? 'ar-YE' : 'en-US',
        viewport: { width: 1365, height: 850 },
        colorScheme: 'light',
      });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(String(e.message).slice(0, 500)));
      const response = await page.goto(root, { waitUntil: 'domcontentloaded', timeout: 45000 });
      await page.waitForTimeout(12000);
      const initial = await page.evaluate(() => ({
        title: document.title,
        htmlLang: document.documentElement.lang,
        htmlDir: document.documentElement.dir,
        computedDir: getComputedStyle(document.body).direction,
        visibleText: (document.body.innerText || '').slice(0, 1200),
        canvasCount: document.querySelectorAll('canvas').length,
        links: [...document.querySelectorAll('a')].filter(a => a.offsetWidth || a.offsetHeight).slice(0, 12).map(a => ({text: a.innerText.slice(0, 80), href: a.getAttribute('href')})),
      }));
      await page.screenshot({ path: path.join(dir, 'nasqo-' + language + '-desktop.png'), fullPage: true, timeout: 15000 });
      const narrow = await context.newPage();
      await narrow.setViewportSize({ width: 390, height: 844 });
      await narrow.goto(root, { waitUntil: 'domcontentloaded', timeout: 45000 });
      await narrow.waitForTimeout(9000);
      await narrow.screenshot({ path: path.join(dir, 'nasqo-' + language + '-mobile.png'), fullPage: true, timeout: 15000 });
      const result = { requestedBrowserLocale: language, httpStatus: response?.status(), finalUrl: page.url(), initial, pageErrors: errors, mobile: await narrow.evaluate(() => ({documentWidth: document.documentElement.scrollWidth, viewportWidth: innerWidth, computedDir: getComputedStyle(document.body).direction})) };
      results.push(result);
      console.log('RESULT', JSON.stringify(result));
      await context.close();
    }
    fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify(results, null, 2));
    if (results.some(r => r.httpStatus !== 200)) process.exitCode = 1;
    if (results.some(r => r.pageErrors.length)) process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
