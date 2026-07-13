const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  page.on('console', msg => console.log(`[CONSOLE] ${msg.type()}: ${msg.text()}`));
  page.on('pageerror', exception => console.log(`[PAGE ERROR] ${exception}`));
  page.on('requestfailed', request => console.log(`[REQUEST FAILED] ${request.url()} - ${request.failure().errorText}`));

  console.log("Navigating to http://localhost/...");
  // Use 'load' instead of 'networkidle' because SSE connection (/api/sse/tasks) keeps the network active
  await page.goto('http://localhost/', { waitUntil: 'load' });
  
  const content = await page.content();
  console.log("=== DOM ===");
  console.log(content.substring(0, 1000));
  
  await browser.close();
})();
