const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  page.on('console', msg => console.log(`[CONSOLE] ${msg.type()}: ${msg.text()}`));
  page.on('pageerror', exception => console.log(`[PAGE ERROR] ${exception}`));
  page.on('requestfailed', request => console.log(`[REQUEST FAILED] ${request.url()} - ${request.failure().errorText}`));

  try {
    console.log("Navigating to http://localhost/...");
    // PAS de waitUntil:'networkidle' : l'application ouvre un flux SSE permanent
    // (/api/sse/tasks), donc le réseau n'est JAMAIS inactif — le test expirait
    // systématiquement au bout de 30 s. On attend à la place que React ait
    // effectivement rendu quelque chose dans #root.
    await page.goto('http://localhost/', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#root > *', { state: 'attached', timeout: 60000 });

    const content = await page.content();
    console.log("=== DOM ===");
    console.log(content.substring(0, 1000));

    await browser.close();
  } catch (e) {
    console.error("E2E failure:", e);
    await browser.close();
    process.exit(1);
  }
})();
