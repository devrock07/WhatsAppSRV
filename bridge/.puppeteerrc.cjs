const path = require('node:path');

module.exports = {
  // ARM64 uses the Sparticuz browser selected by index.js. Linux x64 needs
  // Puppeteer's managed Chrome when the host does not provide one.
  skipDownload: process.platform === 'linux' && process.arch === 'arm64',
  cacheDirectory: process.env.PUPPETEER_CACHE_DIR || path.join(__dirname, '.wwebjs_cache')
};
