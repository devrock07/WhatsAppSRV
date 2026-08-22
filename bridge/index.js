'use strict';

const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const qrcode = require('qrcode-terminal');
const QRCode = require('qrcode');
const { Client, LocalAuth, MessageMedia } = require('whatsapp-web.js');

const configPath = path.join(__dirname, 'config.json');
if (!fs.existsSync(configPath)) {
  console.error('Missing bridge/config.json. Copy config.example.json to config.json and edit it.');
  process.exit(1);
}

const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const host = config.listenHost || '127.0.0.1';
const port = Number(config.listenPort || 3210);
const apiToken = String(config.apiToken || '');
const maxQueuedMessages = Math.max(10, Number(config.maxQueuedMessages || 500));
const maxRemoteImageBytes = 2 * 1024 * 1024;
const maxRequestBodyBytes = 3 * 1024 * 1024;
const supportedImageMimeTypes = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);

function findChrome() {
  const candidates = [
    config.chromeExecutablePath,
    process.env.CHROME_PATH,
    process.env.PROGRAMFILES && path.join(process.env.PROGRAMFILES, 'Google', 'Chrome', 'Application', 'chrome.exe'),
    process.env['PROGRAMFILES(X86)'] && path.join(process.env['PROGRAMFILES(X86)'], 'Google', 'Chrome', 'Application', 'chrome.exe'),
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Google', 'Chrome', 'Application', 'chrome.exe'),
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
  ].filter(Boolean);
  return candidates.find(candidate => fs.existsSync(candidate));
}

if (apiToken.length < 24 || apiToken.includes('replace-with')) {
  console.error('apiToken must be a private random value of at least 24 characters.');
  process.exit(1);
}

let ready = false;
let readinessProbeRunning = false;
let readinessProbeScheduled = false;
let readinessDiagnostic = 'Waiting for authentication';
let detachedFrameFailures = 0;
let recoveryAttempts = 0;
let recoveryRunning = false;
let monitoredBrowser;
let browserStderrTail = '';
let shuttingDown = false;
let browserGone = false;
// Keep queue IDs increasing across bridge reloads. The Java poller remembers
// the last ID it consumed, so restarting at zero would make it skip all new
// inbound messages until that old counter was reached again.
let sequence = Date.now() * 1000;
const inboundQueue = [];
// A poll cursor is not a WhatsApp message id, so keep a bounded association
// between the numeric id exposed to Java and the original Message instance.
// This lets command results quote/react to the exact command without leaking
// WhatsApp's internal (and currently unstable) serialized id shape.
const inboundReferences = new Map();
let client;
let launchOptions;
let browserDescription = 'Puppeteer managed browser';

function serializeModelId(value) {
  if (!value) return '';
  if (typeof value === 'string') return value;
  const direct = value._serialized ?? value.$1;
  if (typeof direct === 'string') return direct;
  if (value.user && value.server) return `${value.user}@${value.server}`;
  if (typeof value.toString === 'function') {
    const converted = String(value.toString());
    if (converted && converted !== '[object Object]') return converted;
  }
  return '';
}

function serializeMessageId(message) {
  return serializeModelId(message?.id) || serializeModelId(message?._data?.id);
}

function inboundMessageChatId(message) {
  return serializeModelId(message?.from) || serializeModelId(message?._data?.from) ||
    serializeModelId(message?._data?.id?.remote);
}

async function reactToInboundMessage(message, reaction) {
  const messageId = serializeMessageId(message);
  if (!messageId) throw new Error('WhatsApp message has no serializable id');
  await client.sendReaction(messageId, reaction);
}

async function inboundMessageStillExists(message) {
  const messageId = serializeMessageId(message);
  if (!messageId) return false;
  return await client.pupPage.evaluate(async messageId => {
    const messages = window.require('WAWebCollections').Msg;
    if (messages.get(messageId)) return true;
    return Boolean((await messages.getMessagesById([messageId]))?.messages?.[0]);
  }, messageId);
}

async function replyToInboundMessage(message, content, options = {}) {
  const messageId = serializeMessageId(message);
  if (!messageId) throw new Error('WhatsApp message has no serializable id');
  const chatId = inboundMessageChatId(message);
  if (!chatId) throw new Error('WhatsApp message has no serializable chat id');
  return await client.sendMessage(chatId, content, {
    ...options,
    quotedMessageId: messageId,
    // Never silently downgrade a command response into an unquoted message.
    // The endpoint reports an expired reference so Java can choose a fallback.
    ignoreQuoteErrors: false
  });
}

async function resolvePuppeteerOptions() {
  const commonArgs = [
    '--no-sandbox',
    '--disable-setuid-sandbox',
    '--disable-dev-shm-usage',
    '--disable-features=IsolateOrigins,site-per-process',
    '--disable-site-isolation-trials',
    '--disable-remote-fonts',
    '--disable-gpu',
    '--renderer-process-limit=2',
    '--js-flags=--max-old-space-size=384',
    '--password-store=basic',
    '--use-mock-keychain'
  ];
  const installedChrome = findChrome();
  if (installedChrome) {
    browserDescription = installedChrome;
    return { headless: true, executablePath: installedChrome, args: commonArgs };
  }

  if (process.platform === 'linux' && process.arch === 'arm64') {
    const imported = await import('@sparticuz/chromium-min');
    const chromium = imported.default;
    chromium.setGraphicsMode = false;
    const packUrl = 'https://github.com/Sparticuz/chromium/releases/download/v149.0.0/chromium-v149.0.0-pack.arm64.tar';
    const executablePath = await chromium.executablePath(packUrl);
    prepareFontConfig();
    browserDescription = `Sparticuz Chromium ARM64 (${executablePath})`;
    return {
      headless: 'shell',
      executablePath,
      // Keep Sparticuz's --single-process/--no-zygote flags. Its ARM headless
      // shell initializes the bundled font manager in that process model;
      // removing them makes remote web fonts abort Skia with SIGTRAP.
      args: [...new Set([...chromium.args, ...commonArgs])]
    };
  }

  return { headless: true, args: commonArgs };
}

function escapeXml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function prepareFontConfig() {
  // Sparticuz's fonts.conf contains /tmp paths. WhatsAppSRV deliberately
  // redirects TMPDIR into plugin storage because small Pterodactyl /tmp
  // mounts previously caused ENOSPC. Rewrite the config to the real expanded
  // location or Chromium's Skia font fallback aborts with SIGTRAP.
  const tempDirectory = os.tmpdir();
  const fontConfigDirectory = path.join(tempDirectory, 'fonts');
  const fontDirectory = path.join(fontConfigDirectory, 'fonts');
  const fontCacheDirectory = path.join(tempDirectory, 'fonts-cache');
  const regularFont = path.join(fontDirectory, 'Open_Sans', 'OpenSans-Regular.ttf');
  if (!fs.existsSync(regularFont)) {
    throw new Error(`Bundled Chromium font is missing: ${regularFont}`);
  }
  fs.mkdirSync(fontCacheDirectory, { recursive: true });
  const fontConfigFile = path.join(fontConfigDirectory, 'fonts.conf');
  const contents = `<?xml version="1.0"?>\n<fontconfig>\n  <dir>${escapeXml(fontDirectory)}</dir>\n  <cachedir>${escapeXml(fontCacheDirectory)}</cachedir>\n  <config></config>\n</fontconfig>\n`;
  fs.writeFileSync(fontConfigFile, contents, 'utf8');
  process.env.FONTCONFIG_PATH = fontConfigDirectory;
  process.env.FONTCONFIG_FILE = fontConfigFile;
  console.log(`Using bundled Fontconfig: ${fontConfigFile} (fonts: ${fontDirectory})`);
}

function markReady(source) {
  if (ready) return;
  ready = true;
  readinessDiagnostic = `Ready (${source})`;
  console.log(`WhatsApp client is ready (${source}).`);
  if (!config.targetChatId) {
    console.log('targetChatId is empty. Request GET /chats with the API token, choose an id, then restart.');
  } else {
    console.log(`Configured target: ${config.targetChatId}`);
  }
}

async function listChatTargets() {
  // Current WhatsApp Web renamed Wid._serialized to Wid.$1. Calling
  // whatsapp-web.js getChats() serializes full chat models and throws the
  // opaque `r: r` error when it encounters the renamed field. The bridge only
  // needs an id, display name and group flag, so read those minimal fields with
  // compatibility for both layouts.
  return await client.pupPage.evaluate(() => {
    const serializeWid = wid => {
      if (!wid) return '';
      const direct = wid._serialized ?? wid.$1;
      if (typeof direct === 'string') return direct;
      if (typeof wid.toString === 'function') {
        const value = String(wid.toString());
        if (value && value !== '[object Object]') return value;
      }
      if (wid.user && wid.server) return `${wid.user}@${wid.server}`;
      return '';
    };

    const chats = window.require('WAWebCollections').Chat.getModelsArray();
    return chats.map(chat => {
      const id = serializeWid(chat.id);
      // formattedTitle is normally the visible WhatsApp group subject. Keep
      // the metadata fallbacks for WhatsApp Web builds that expose it there.
      const name = chat.formattedTitle || chat.name || chat.displayName ||
        chat.groupMetadata?.subject || chat.groupMetadata?.groupSubject ||
        chat.contact?.pushname || chat.contact?.name ||
        chat.contact?.formattedName || chat.contact?.verifiedName || id;
      return {
        id,
        name: String(name || ''),
        isGroup: Boolean(chat.isGroup || chat.groupMetadata || id.endsWith('@g.us'))
      };
    }).filter(chat => chat.id);
  });
}

function createClient() {
  browserGone = false;
  client = new Client({
    authStrategy: new LocalAuth({ dataPath: path.resolve(__dirname, config.sessionPath || '.wwebjs_auth') }),
    puppeteer: launchOptions
  });
  client.on('error', error => console.error('WhatsApp client error:', error));
  attachClientHandlers();
}

function monitorBrowser() {
  const browser = client?.pupBrowser;
  if (!browser) {
    if (!shuttingDown) setTimeout(monitorBrowser, 250);
    return;
  }
  if (monitoredBrowser === browser) return;
  monitoredBrowser = browser;
  browserStderrTail = '';
  browser.on('disconnected', () => {
    if (shuttingDown) return;
    ready = false;
    browserGone = true;
    readinessDiagnostic = 'Chromium disconnected unexpectedly';
    console.error('Chromium disconnected unexpectedly.');
  });
  const browserProcess = browser.process();
  if (browserProcess?.stderr) {
    browserProcess.stderr.on('data', chunk => {
      browserStderrTail = (browserStderrTail + String(chunk)).slice(-8192);
    });
  }
  browserProcess?.once('exit', (code, signal) => {
    if (shuttingDown) return;
    readinessDiagnostic = `Chromium exited: code=${code}, signal=${signal || 'none'}`;
    browserGone = true;
    console.error(readinessDiagnostic);
    if (browserStderrTail.trim()) console.error(`Chromium stderr before exit:\n${browserStderrTail.trim()}`);
  });
}

async function recoverDetachedBrowserFrame() {
  if (recoveryRunning || recoveryAttempts >= 1) return;
  recoveryRunning = true;
  recoveryAttempts++;
  readinessDiagnostic = 'Reattaching to WhatsApp after post-login navigation';
  console.log('Post-login browser frame detached. Reattaching inside the authenticated browser...');
  try {
    // Keep the browser and its live LocalAuth storage. A new page in the same
    // browser context shares the authenticated cookies and IndexedDB, while
    // avoiding Puppeteer's permanently detached page frame.
    const previousPage = client.pupPage;
    const replacementPage = await client.pupBrowser.newPage();
    await replacementPage.setUserAgent(client.options.userAgent);
    client.pupPage = replacementPage;
    client.currentIndexHtml = null;
    await client.initWebVersionCache();
    await replacementPage.goto('https://web.whatsapp.com/', {
      waitUntil: 'domcontentloaded',
      timeout: 60000
    });
    if (previousPage && previousPage !== replacementPage) {
      await previousPage.close().catch(() => {});
    }
    await client.inject();
    readinessDiagnostic = 'Browser frame reattached; verifying chat access';
    console.log('Authenticated WhatsApp page reattached successfully.');
  } catch (error) {
    readinessDiagnostic = `Browser reattachment failed: ${error?.message || error}`;
    console.error(readinessDiagnostic);
  } finally {
    detachedFrameFailures = 0;
    recoveryRunning = false;
  }
}

async function probeReadiness() {
  if (readinessProbeRunning || recoveryRunning || browserGone || ready || !client?.pupPage) return;
  readinessProbeRunning = true;
  try {
    const details = await Promise.race([
      client.pupPage.evaluate(() => {
      let mode = 'unknown';
      let displayInfo = 'unknown';
      let socketState = 'unknown';
      try {
        const { Stream } = window.require('WAWebStreamModel');
        mode = String(Stream.mode ?? 'unknown');
        displayInfo = String(Stream.displayInfo ?? 'unknown');
      } catch (_) {}
      try {
        socketState = String(window.require('WAWebSocketModel').Socket.state ?? 'unknown');
      } catch (_) {}
      return {
        mode,
        displayInfo,
        socketState,
        injected: typeof window.WWebJS !== 'undefined',
        canListChats: typeof window.WWebJS?.getChats === 'function'
      };
      }),
      new Promise((_, reject) => setTimeout(() => reject(new Error('WhatsApp page did not answer readiness check within 15 seconds')), 15000))
    ]);
    readinessDiagnostic = `mode=${details.mode}, display=${details.displayInfo}, socket=${details.socketState}, injected=${details.injected}`;
    console.log(`WhatsApp readiness check: ${readinessDiagnostic}`);

    const connected = details.mode === 'MAIN' || details.socketState === 'CONNECTED';
    if (connected && details.canListChats) {
      await listChatTargets();
      markReady('verified chat access');
    }
    detachedFrameFailures = 0;
  } catch (error) {
    readinessDiagnostic = `Readiness check failed: ${error?.message || error}`;
    console.error(readinessDiagnostic);
    if (/detached Frame/i.test(String(error?.message || error))) {
      detachedFrameFailures++;
      if (detachedFrameFailures >= 2) await recoverDetachedBrowserFrame();
    }
  } finally {
    readinessProbeRunning = false;
  }
}

function startReadinessProbes() {
  if (readinessProbeScheduled) return;
  readinessProbeScheduled = true;
  const run = async () => {
    await probeReadiness();
    if (!ready && !browserGone) {
      setTimeout(run, 5000);
    } else {
      readinessProbeScheduled = false;
    }
  };
  setTimeout(run, 2000);
}

function attachClientHandlers() {
client.on('qr', qr => {
  console.log('Scan this QR code in WhatsApp: Linked devices > Link a device');
  qrcode.generate(qr, { small: true });
  const qrPath = path.join(__dirname, 'qr.png');
  QRCode.toFile(qrPath, qr, { width: 512, margin: 2 })
    .then(() => console.log(`QR image saved to ${qrPath}`))
    .catch(error => console.error('Could not save QR image:', error));
});

client.on('authenticated', () => {
  readinessDiagnostic = 'Authenticated; verifying WhatsApp chat access';
  console.log('WhatsApp session authenticated.');
  startReadinessProbes();
});
client.on('loading_screen', (percent, message) => {
  console.log(`WhatsApp loading: ${percent}% ${message || ''}`.trim());
});
client.on('change_state', state => console.log(`WhatsApp connection state: ${state}`));
client.on('auth_failure', message => console.error('WhatsApp authentication failed:', message));
client.on('disconnected', reason => {
  ready = false;
  console.error('WhatsApp disconnected:', reason);
});

client.on('ready', () => markReady('library event'));

client.on('message', async message => {
  if (!config.receiveFromTarget || message.fromMe) return;

  const serializeWid = wid => {
    if (!wid) return '';
    if (typeof wid === 'string') return wid;
    const direct = wid._serialized ?? wid.$1;
    if (typeof direct === 'string') return direct;
    if (wid.user && wid.server) return `${wid.user}@${wid.server}`;
    return '';
  };
  const incomingChatId = serializeWid(message.from) ||
    serializeWid(message._data?.from) || serializeWid(message._data?.id?.remote);
  if (!config.targetChatId || incomingChatId !== config.targetChatId) return;

  try {
    const body = String(message.body || '').trim();
    const isCommand = body.startsWith('/');
    // Allocate the reference before doing contact/network lookups. Commands
    // can then be acknowledged as soon as WhatsApp delivers them.
    const referenceId = ++sequence;
    inboundReferences.set(referenceId, message);
    while (inboundReferences.size > maxQueuedMessages) {
      inboundReferences.delete(inboundReferences.keys().next().value);
    }
    if (isCommand) {
      try {
        await reactToInboundMessage(message, '⏳');
      } catch (error) {
        // A command must still reach Minecraft if WhatsApp rejects a reaction.
        console.warn(`Could not acknowledge WhatsApp command ${referenceId}:`, error?.message || error);
      }
    }

    const senderId = serializeWid(message.author) ||
      serializeWid(message._data?.author) ||
      serializeWid(message._data?.participant) ||
      serializeWid(message._data?.id?.participant) || incomingChatId;
    let sender = senderId;
    try {
      const contact = await message.getContact();
      sender = contact.pushname || contact.name || contact.number || sender;
    } catch (error) {
      console.warn(`Could not resolve WhatsApp sender ${sender}; using its ID:`, error?.message || error);
    }
    let groupRole = {
      isGroup: incomingChatId.endsWith('@g.us'),
      isAdmin: false,
      isSuperAdmin: false
    };
    if (groupRole.isGroup) {
      try {
        groupRole = await resolveGroupRole(incomingChatId, senderId);
      } catch (error) {
        // Do not grant permissions when WhatsApp changes an internal model.
        console.warn(`Could not resolve WhatsApp group role for ${senderId}:`, error?.message || error);
      }
    }

    const messageType = String(message.type || 'unknown');
    const mediaFilename = String(message._data?.filename || '').trim();
    const mediaMimeType = String(message._data?.mimetype || '').trim();
    const mediaLabels = {
      sticker: '🧩 [Sticker]',
      image: '🖼️ [Image]',
      video: '🎬 [Video]',
      audio: '🎵 [Audio]',
      ptt: '🎤 [Voice message]',
      document: `📎 [Document${mediaFilename ? `: ${mediaFilename}` : ''}]`,
      location: '📍 [Location]',
      vcard: '👤 [Contact]',
      multi_vcard: '👥 [Contacts]'
    };
    const mediaLabel = mediaLabels[messageType] || (message.hasMedia ? '📎 [Media]' : '');
    // Minecraft chat cannot render arbitrary WhatsApp binary media. Preserve
    // ordinary Unicode/emoji text unchanged, and turn media into a concise,
    // readable chat entry (including an image/video caption when one exists).
    const text = mediaLabel ? `${mediaLabel}${body ? ` ${body}` : ''}` : body;
    if (!text) {
      inboundReferences.delete(referenceId);
      return;
    }
    inboundQueue.push({
      id: referenceId,
      referenceId,
      sender: String(sender),
      senderId: String(senderId),
      text,
      type: messageType,
      isGroup: Boolean(groupRole.isGroup),
      isAdmin: Boolean(groupRole.isAdmin),
      isSuperAdmin: Boolean(groupRole.isSuperAdmin),
      hasMedia: Boolean(message.hasMedia),
      mediaMimeType,
      mediaFilename,
      timestamp: Number(message.timestamp || Math.floor(Date.now() / 1000))
    });
    while (inboundQueue.length > maxQueuedMessages) inboundQueue.shift();
  } catch (error) {
    console.error('Failed to queue incoming WhatsApp message:', error);
  }
});
}

async function resolveGroupRole(chatId, senderId) {
  // Avoid Client#getChatById/getChats here. Current WhatsApp Web exposes some
  // Wids as `$1` instead of `_serialized`; serializing a complete Chat model
  // therefore throws the opaque `r: r` error. Read only the participant role
  // fields from the live Store and accept both Wid layouts.
  return await client.pupPage.evaluate(async ({ chatId, senderId }) => {
    const serializeWid = wid => {
      if (!wid) return '';
      if (typeof wid === 'string') return wid;
      const direct = wid._serialized ?? wid.$1;
      if (typeof direct === 'string') return direct;
      if (wid.user && wid.server) return `${wid.user}@${wid.server}`;
      if (typeof wid.toString === 'function') {
        const value = String(wid.toString());
        if (value && value !== '[object Object]') return value;
      }
      return '';
    };
    const asModels = collection => {
      if (!collection) return [];
      if (Array.isArray(collection)) return collection;
      if (typeof collection.getModelsArray === 'function') return collection.getModelsArray();
      if (Array.isArray(collection.models)) return collection.models;
      if (Array.isArray(collection._models)) return collection._models;
      return [];
    };
    const identifiersFor = participant => {
      const ids = [
        participant?.id,
        participant?.wid,
        participant?.contact?.id,
        participant?.phoneNumber,
        participant?.pn,
        participant?.lid
      ];
      try {
        const { toPn } = window.require('WAWebLidMigrationUtils');
        const participantWid = typeof participant?.id === 'string'
          ? window.require('WAWebWidFactory').createWid(participant.id)
          : participant?.id;
        ids.push(toPn(participantWid));
      } catch (_) {}
      return ids.map(serializeWid).filter(Boolean);
    };
    const chatCollection = window.require('WAWebCollections').Chat;
    const findChat = () => {
      try {
        const direct = chatCollection.get(chatId);
        if (direct) return direct;
      } catch (_) {}
      return asModels(chatCollection).find(chat => serializeWid(chat.id) === chatId);
    };
    let chat = findChat();
    let participants = asModels(chat?.groupMetadata?.participants);
    let participant = participants.find(item => identifiersFor(item).includes(senderId));

    // Metadata is normally already present for the active group. If it is not,
    // refresh just this group's metadata and retry without serializing a Chat.
    if (!participant) {
      try {
        const wid = window.require('WAWebWidFactory').createWid(chatId);
        const metadataCollection = window.require('WAWebCollections').GroupMetadata ||
          window.require('WAWebCollections').WAWebGroupMetadataCollection;
        await metadataCollection?.update?.(wid);
        chat = findChat();
        participants = asModels(chat?.groupMetadata?.participants);
        participant = participants.find(item => identifiersFor(item).includes(senderId));
      } catch (_) {}
    }

    return {
      isGroup: Boolean(chat?.groupMetadata || chatId.endsWith('@g.us')),
      isAdmin: Boolean(participant?.isAdmin),
      isSuperAdmin: Boolean(participant?.isSuperAdmin)
    };
  }, { chatId, senderId });
}

function authorized(request) {
  const supplied = String(request.headers.authorization || '').replace(/^Bearer\s+/i, '');
  const expectedBuffer = Buffer.from(apiToken);
  const suppliedBuffer = Buffer.from(supplied);
  return expectedBuffer.length === suppliedBuffer.length && crypto.timingSafeEqual(expectedBuffer, suppliedBuffer);
}

function respond(response, status, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store'
  });
  response.end(body);
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    let body = '';
    let receivedBytes = 0;
    let tooLarge = false;
    request.setEncoding('utf8');
    request.on('data', chunk => {
      if (tooLarge) return;
      receivedBytes += Buffer.byteLength(chunk, 'utf8');
      if (receivedBytes > maxRequestBodyBytes) {
        tooLarge = true;
        const error = new Error(`Request body may not exceed ${maxRequestBodyBytes} bytes`);
        error.statusCode = 413;
        reject(error);
        return;
      }
      body += chunk;
    });
    request.on('end', () => {
      if (tooLarge) return;
      try {
        resolve(JSON.parse(body || '{}'));
      } catch (error) {
        error.statusCode = 400;
        reject(error);
      }
    });
    request.on('error', reject);
  });
}

function parseRemoteImageUrl(value) {
  const raw = String(value || '').trim();
  if (!raw) return null;
  if (raw.length > 2048) throw new Error('mediaUrl is too long');
  const parsed = new URL(raw);
  if (parsed.protocol !== 'https:') throw new Error('mediaUrl must use HTTPS');
  return parsed.href;
}

function parseInlineImage(payload) {
  const data = String(payload.mediaData || '').trim();
  if (!data) return null;
  const mimeType = String(payload.mediaMimeType || '').split(';', 1)[0].trim().toLowerCase();
  if (!supportedImageMimeTypes.has(mimeType)) {
    throw new Error('mediaMimeType must be image/png, image/jpeg, image/webp, or image/gif');
  }
  // Reject malformed or oversized base64 before decoding it. A 2 MiB file is
  // at most 2,796,204 base64 characters including padding.
  const maxBase64Length = Math.ceil(maxRemoteImageBytes / 3) * 4;
  if (data.length > maxBase64Length) throw new Error('inline image exceeds the 2 MiB limit');
  if (data.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(data)) {
    throw new Error('mediaData must be valid base64 without a data-URL prefix');
  }
  const decodedSize = Buffer.from(data, 'base64').length;
  if (!decodedSize || decodedSize > maxRemoteImageBytes) {
    throw new Error('inline image must be between 1 byte and 2 MiB');
  }
  const rawFilename = String(payload.mediaFilename || 'minecraft-head.png').trim();
  const filename = rawFilename.split(/[\\/]/).pop().slice(0, 128) || 'minecraft-head.png';
  return new MessageMedia(mimeType, data, filename, decodedSize);
}

async function downloadRemoteImage(mediaUrl) {
  const media = await MessageMedia.fromUrl(mediaUrl, {
    unsafeMime: true,
    reqOptions: {
      size: maxRemoteImageBytes,
      timeout: 15000,
      follow: 5,
      headers: {
        Accept: 'image/*',
        'User-Agent': 'WhatsAppSRV/1.0'
      }
    }
  });
  const mimeType = String(media.mimetype || '').split(';', 1)[0].trim().toLowerCase();
  const decodedSize = Buffer.from(media.data || '', 'base64').length;
  if (!supportedImageMimeTypes.has(mimeType)) {
    throw new Error('mediaUrl did not return a supported PNG, JPEG, WebP, or GIF image');
  }
  if (!decodedSize || decodedSize > maxRemoteImageBytes) {
    throw new Error(`image must be between 1 byte and ${maxRemoteImageBytes} bytes`);
  }
  media.mimetype = mimeType;
  return media;
}

const server = http.createServer(async (request, response) => {
  try {
    if (!authorized(request)) return respond(response, 401, { error: 'Unauthorized' });

    const url = new URL(request.url, `http://${host}:${port}`);
    if (request.method === 'GET' && url.pathname === '/health') {
      return respond(response, 200, { ready, targetConfigured: Boolean(config.targetChatId), diagnostic: readinessDiagnostic });
    }

    if (request.method === 'GET' && url.pathname === '/chats') {
      if (!ready) return respond(response, 503, { error: 'WhatsApp is not ready', diagnostic: readinessDiagnostic });
      return respond(response, 200, await listChatTargets());
    }

    if (request.method === 'GET' && url.pathname === '/messages') {
      const after = Number(url.searchParams.get('after') || 0);
      return respond(response, 200, inboundQueue.filter(message => message.id > after));
    }

    if (request.method === 'POST' && url.pathname === '/send') {
      if (!ready) return respond(response, 503, { error: 'WhatsApp is not ready', diagnostic: readinessDiagnostic });
      if (!config.targetChatId) return respond(response, 409, { error: 'targetChatId is not configured' });
      const payload = await readJson(request);
      const text = String(payload.text || '').trim();
      let mediaUrl;
      let media;
      try {
        mediaUrl = parseRemoteImageUrl(payload.mediaUrl);
        media = parseInlineImage(payload);
        if (mediaUrl && media) throw new Error('provide either mediaUrl or mediaData, not both');
      } catch (error) {
        return respond(response, 400, { error: error.message });
      }
      if (!text && !mediaUrl && !media) {
        return respond(response, 400, { error: 'text, mediaUrl, or mediaData is required' });
      }
      if (text.length > 4096) return respond(response, 400, { error: 'text is too long' });
      if (mediaUrl) {
        try {
          media = await downloadRemoteImage(mediaUrl);
        } catch (error) {
          console.error(`Could not download outbound image ${mediaUrl}:`, error);
          return respond(response, 502, { error: 'Could not download the outbound image' });
        }
      }
      if (media) {
        await client.sendMessage(config.targetChatId, media, { caption: text });
        return respond(response, 200, { sent: true, media: true });
      }
      await client.sendMessage(config.targetChatId, text);
      return respond(response, 200, { sent: true, media: false });
    }

    if (request.method === 'POST' && url.pathname === '/reply') {
      if (!ready) return respond(response, 503, { error: 'WhatsApp is not ready', diagnostic: readinessDiagnostic });
      const payload = await readJson(request);
      const referenceId = Number(payload.referenceId);
      if (!Number.isSafeInteger(referenceId) || referenceId <= 0) {
        return respond(response, 400, { error: 'referenceId must be a positive integer' });
      }
      const originalMessage = inboundReferences.get(referenceId);
      if (!originalMessage) {
        return respond(response, 404, { error: 'Message reference expired; send the command again' });
      }

      const hasReaction = Object.prototype.hasOwnProperty.call(payload, 'reaction') && payload.reaction !== null;
      const reaction = hasReaction ? String(payload.reaction) : null;
      const text = String(payload.text || '').trim();
      if (reaction !== null && reaction.length > 32) {
        return respond(response, 400, { error: 'reaction is too long' });
      }
      if (text.length > 4096) return respond(response, 400, { error: 'text is too long' });

      let media;
      try {
        media = parseInlineImage(payload);
      } catch (error) {
        return respond(response, 400, { error: error.message });
      }
      if (!hasReaction && !text && !media) {
        return respond(response, 400, { error: 'text, mediaData, or reaction is required' });
      }

      let reacted = false;
      try {
        // Updating the hourglass and quoting the result are deliberately one
        // request so a fast command cannot leave an obsolete reaction behind.
        if (!await inboundMessageStillExists(originalMessage)) {
          inboundReferences.delete(referenceId);
          return respond(response, 404, { error: 'Message reference expired; send the command again' });
        }
        if (hasReaction) {
          try {
            await reactToInboundMessage(originalMessage, reaction);
            reacted = true;
          } catch (reactionError) {
            // A WhatsApp build/account can reject reactions while still
            // allowing quoted replies. Never lose the command result for a
            // cosmetic acknowledgement failure.
            console.warn(`Could not update reaction for command ${referenceId}:`,
              reactionError?.message || reactionError);
            if (!text && !media) throw reactionError;
          }
        }
        if (media) {
          await replyToInboundMessage(originalMessage, media, { caption: text });
        } else if (text) {
          await replyToInboundMessage(originalMessage, text);
        }
      } catch (error) {
        // The bounded reference can outlive WhatsApp's in-page model cache.
        // Treat a missing/detached original as an expired reference instead of
        // turning a harmless late command result into an opaque HTTP 500.
        const detail = String(error?.message || error);
        if (/not found|does not exist|could not get the quoted message|invalid serialized message|detached frame|execution context was destroyed/i.test(detail)) {
          inboundReferences.delete(referenceId);
          return respond(response, 404, { error: 'Message reference expired; send the command again' });
        }
        throw error;
      }
      return respond(response, 200, {
        replied: Boolean(text || media),
        reacted,
        media: Boolean(media)
      });
    }

    return respond(response, 404, { error: 'Not found' });
  } catch (error) {
    console.error('Bridge request failed:', error);
    const statusCode = Number(error?.statusCode);
    if (statusCode >= 400 && statusCode < 500) return respond(response, statusCode, { error: error.message });
    return respond(response, 500, { error: 'Internal bridge error' });
  }
});

async function start() {
  launchOptions = await resolvePuppeteerOptions();
  createClient();
  server.listen(port, host, () => {
    console.log(`Local bridge API listening on http://${host}:${port}`);
    console.log(`Using browser: ${browserDescription}`);
    client.initialize().catch(error => console.error('Failed to initialize WhatsApp:', error));
    monitorBrowser();
  });
}

async function shutdown() {
  shuttingDown = true;
  console.log('Shutting down WhatsApp bridge...');
  server.close();
  try {
    if (client) await client.destroy();
  } finally {
    process.exit(0);
  }
}

process.once('SIGINT', shutdown);
process.once('SIGTERM', shutdown);
start().catch(error => {
  console.error('Failed to prepare WhatsApp bridge:', error);
  process.exit(1);
});
