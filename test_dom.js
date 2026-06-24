const { execSync } = require('child_process');
const fs = require('fs');

async function test() {
  try {
    // Start Chrome in headless mode with remote debugging
    console.log("Starting Chrome...");
    const cmd = `"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe" --headless --disable-gpu --dump-dom http://localhost:5173/`;
    const dom = execSync(cmd, { encoding: 'utf-8', stdio: ['pipe', 'pipe', 'ignore'] });
    console.log("=== DOM DUMP ===");
    console.log(dom);
  } catch (e) {
    console.error("Failed:", e.message);
  }
}

test();
