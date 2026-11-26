import http from "node:http";
import path from "node:path";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { createHash, randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";

function option(flag, fallback) {
  const index = process.argv.indexOf(flag);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const PORT = Number(option("--port", "8787"));
const DATA_DIR = path.resolve(option("--data-dir", "./data"));
const DATA_FILE = path.join(DATA_DIR, "devices.json");
const AUTH_FILE = path.join(DATA_DIR, "admin-auth.json");
const PUBLIC_DIR = path.resolve(process.cwd(), "public");
const CONTENT_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml",
  ".webmanifest": "application/manifest+json",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".ico": "image/x-icon"
};
const SESSION_TTL_MS = 12 * 60 * 60 * 1000;
const ONLINE_WINDOW_MS = 90 * 1000;
const MAX_BODY_BYTES = 16 * 1024;
const sessions = new Map();
const rateLimits = new Map();

if (!Number.isInteger(PORT) || PORT < 1 || PORT > 65535) {
  throw new Error("Port must be a valid TCP port.");
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function safeEqual(left, right) {
  const a = Buffer.from(String(left));
  const b = Buffer.from(String(right));
  return a.length === b.length && timingSafeEqual(a, b);
}

function bearerToken(request) {
  const match = /^Bearer\s+(.+)$/i.exec(request.headers.authorization ?? "");
  return match?.[1] ?? "";
}

function sendJson(response, status, body) {
  const payload = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": payload.length,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
  });
  response.end(payload);
}

function isPrivateHost(host) {
  if (host === "localhost" || host === "127.0.0.1" || host === "::1") return true;
  const parts = host.split(".");
  if (parts.length !== 4) return false;
  const octets = parts.map(Number);
  if (octets.some((value) => !Number.isInteger(value) || value < 0 || value > 255)) return false;
  return octets[0] === 10
    || octets[0] === 127
    || (octets[0] === 192 && octets[1] === 168)
    || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31);
}

function setCors(request, response) {
  const origin = request.headers.origin;
  if (!origin) return true;
  let parsed;
  try {
    parsed = new URL(origin);
  } catch {
    sendJson(response, 403, { error: "Invalid browser origin." });
    return false;
  }
  const allowed = parsed.protocol === "https:"
    || (parsed.protocol === "http:" && isPrivateHost(parsed.hostname));
  if (!allowed) {
    sendJson(response, 403, { error: "Use HTTPS or a trusted local network." });
    return false;
  }
  response.setHeader("Access-Control-Allow-Origin", origin);
  response.setHeader("Vary", "Origin");
  response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
  response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  return true;
}

async function readBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      const error = new Error("Request body is too large.");
      error.statusCode = 413;
      throw error;
    }
    chunks.push(chunk);
  }
  if (chunks.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    const error = new Error("Request body must be valid JSON.");
    error.statusCode = 400;
    throw error;
  }
}

function cleanText(value, maxLength = 80) {
  if (typeof value !== "string") return "";
  return value.trim().replace(/[\u0000-\u001f\u007f]/g, "").slice(0, maxLength);
}

function cleanMetadata(input = {}) {
  const battery = input.batteryPercent;
  return {
    manufacturer: cleanText(input.manufacturer, 50),
    model: cleanText(input.model, 80),
    androidVersion: cleanText(input.androidVersion, 30),
    securityPatch: cleanText(input.securityPatch, 20),
    batteryPercent: typeof battery === "number" && Number.isFinite(battery)
      ? Math.max(0, Math.min(100, Math.round(battery)))
      : null,
    charging: typeof input.charging === "boolean" ? input.charging : null,
    appVersion: cleanText(input.appVersion, 30),
  };
}

async function loadJson(file, fallback) {
  try {
    return JSON.parse(await readFile(file, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") return fallback;
    throw error;
  }
}

async function saveJson(file, value) {
  await mkdir(DATA_DIR, { recursive: true });
  const temporary = file + ".tmp";
  await writeFile(temporary, JSON.stringify(value, null, 2), { mode: 0o600 });
  await rename(temporary, file);
}

let devices = await loadJson(DATA_FILE, []);
let adminAuth = await loadJson(AUTH_FILE, null);

function rateLimited(key, limit, windowMs) {
  const now = Date.now();
  const state = rateLimits.get(key) ?? { count: 0, resetAt: now + windowMs };
  if (state.resetAt <= now) {
    state.count = 0;
    state.resetAt = now + windowMs;
  }
  state.count += 1;
  rateLimits.set(key, state);
  return state.count > limit;
}

function isLoopback(request) {
  const address = request.socket.remoteAddress ?? "";
  const hostHeader = (request.headers.host ?? "").split(":")[0].replace(/^\[|\]$/g, "");
  return (address === "127.0.0.1" || address === "::1" || address === "::ffff:127.0.0.1")
    && (hostHeader === "localhost" || hostHeader === "127.0.0.1" || hostHeader === "::1");
}

function sessionFor(request) {
  const token = bearerToken(request);
  if (!token) return null;
  const digest = sha256(token);
  const expiresAt = sessions.get(digest);
  if (!expiresAt || expiresAt <= Date.now()) {
    sessions.delete(digest);
    return null;
  }
  return digest;
}

function requireOperator(request, response) {
  if (!sessionFor(request)) {
    sendJson(response, 401, { error: "Sign in to SkySpy Control first." });
    return false;
  }
  return true;
}

function publicDevice(device) {
  const { tokenHash, installIdHash, ...safe } = device;
  return {
    ...safe,
    status: Date.now() - Date.parse(device.lastSeenAt) <= ONLINE_WINDOW_MS ? "online" : "offline",
  };
}

function deviceForToken(token) {
  if (!token) return null;
  const digest = sha256(token);
  return devices.find((device) => safeEqual(device.tokenHash, digest)) ?? null;
}

function validInstallId(value) {
  return typeof value === "string"
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}


async function serveControlApp(route, response) {
  if (route.startsWith("/api/") || route === "/health") return false;
  let requestedPath;
  try {
    requestedPath = decodeURIComponent(route.split("?")[0] || "/");
  } catch {
    return false;
  }
  const relative = requestedPath === "/" ? "index.html" : requestedPath.replace(/^\/+/, "");
  const filePath = path.resolve(PUBLIC_DIR, relative);
  if (filePath !== PUBLIC_DIR && !filePath.startsWith(PUBLIC_DIR + path.sep)) return false;

  let bytes;
  let resolvedPath = filePath;
  try {
    bytes = await readFile(resolvedPath);
  } catch (error) {
    if (error.code !== "ENOENT" || path.extname(requestedPath)) return false;
    resolvedPath = path.join(PUBLIC_DIR, "index.html");
    try {
      bytes = await readFile(resolvedPath);
    } catch {
      return false;
    }
  }

  const extension = path.extname(resolvedPath).toLowerCase();
  response.writeHead(200, {
    "Content-Type": CONTENT_TYPES[extension] ?? "application/octet-stream",
    "Content-Length": bytes.length,
    "Cache-Control": extension === ".html" ? "no-cache" : "public, max-age=31536000, immutable",
    "X-Content-Type-Options": "nosniff"
  });
  response.end(bytes);
  return true;
}

async function handle(request, response) {
  if (!setCors(request, response)) return;
  if (request.method === "OPTIONS") {
    response.writeHead(204);
    response.end();
    return;
  }

  const url = new URL(request.url ?? "/", "http://localhost");
  const route = url.pathname;
  const address = request.socket.remoteAddress ?? "unknown";

  if (request.method === "GET" && route === "/health") {
    sendJson(response, 200, { ok: true, service: "skyspy-api", version: "0.2.0" });
    return;
  }

  if (request.method === "GET" && route === "/api/auth/status") {
    sendJson(response, 200, { configured: Boolean(adminAuth) });
    return;
  }

  if (request.method === "POST" && route === "/api/auth/setup") {
    if (!isLoopback(request)) {
      sendJson(response, 403, { error: "Create the first admin account from the server computer." });
      return;
    }
    if (adminAuth) {
      sendJson(response, 409, { error: "Admin account already exists. Sign in instead." });
      return;
    }
    const body = await readBody(request);
    const password = typeof body.password === "string" ? body.password : "";
    if (password.length < 12 || password.length > 128) {
      sendJson(response, 400, { error: "Admin password must be between 12 and 128 characters." });
      return;
    }
    const salt = randomBytes(16).toString("hex");
    const passwordHash = scryptSync(password, salt, 64).toString("hex");
    adminAuth = { salt, passwordHash, createdAt: new Date().toISOString() };
    await saveJson(AUTH_FILE, adminAuth);
    const token = randomBytes(32).toString("base64url");
    sessions.set(sha256(token), Date.now() + SESSION_TTL_MS);
    sendJson(response, 201, { token, expiresInSeconds: SESSION_TTL_MS / 1000 });
    return;
  }

  if (request.method === "POST" && route === "/api/auth/login") {
    if (!adminAuth) {
      sendJson(response, 409, { error: "Create the admin account on the server computer first." });
      return;
    }
    if (rateLimited("login:" + address, 8, 60_000)) {
      sendJson(response, 429, { error: "Too many login attempts. Try again in one minute." });
      return;
    }
    const body = await readBody(request);
    const password = typeof body.password === "string" ? body.password : "";
    const candidate = scryptSync(password, adminAuth.salt, 64).toString("hex");
    if (!safeEqual(candidate, adminAuth.passwordHash)) {
      sendJson(response, 401, { error: "Incorrect admin password." });
      return;
    }
    const token = randomBytes(32).toString("base64url");
    sessions.set(sha256(token), Date.now() + SESSION_TTL_MS);
    sendJson(response, 200, { token, expiresInSeconds: SESSION_TTL_MS / 1000 });
    return;
  }

  if (request.method === "POST" && route === "/api/auth/logout") {
    const digest = sessionFor(request);
    if (digest) sessions.delete(digest);
    sendJson(response, 200, { ok: true });
    return;
  }

  if (request.method === "POST" && route === "/api/device/register") {
    if (rateLimited("register:" + address, 12, 60_000)) {
      sendJson(response, 429, { error: "Too many registration attempts from this network." });
      return;
    }
    const body = await readBody(request);
    if (body.consent !== true) {
      sendJson(response, 403, { error: "The device owner must approve sharing the listed diagnostics." });
      return;
    }
    if (!validInstallId(body.installId)) {
      sendJson(response, 400, { error: "Companion installation identity is invalid." });
      return;
    }
    const metadata = cleanMetadata(body.device);
    if (!metadata.model) {
      sendJson(response, 400, { error: "Device model is required." });
      return;
    }

    const installIdHash = sha256(body.installId);
    let device = devices.find((item) => safeEqual(item.installIdHash, installIdHash));
    const deviceToken = randomBytes(32).toString("base64url");
    const now = new Date().toISOString();

    if (device) {
      device = Object.assign(device, metadata, {
        tokenHash: sha256(deviceToken),
        lastSeenAt: now,
        name: cleanText(body.deviceName, 60) || metadata.model,
      });
    } else {
      device = {
        id: randomUUID(),
        installIdHash,
        name: cleanText(body.deviceName, 60) || metadata.model,
        ...metadata,
        tokenHash: sha256(deviceToken),
        createdAt: now,
        lastSeenAt: now,
      };
      devices.push(device);
    }
    await saveJson(DATA_FILE, devices);
    sendJson(response, 201, {
      deviceId: device.id,
      deviceToken,
      registeredAt: device.createdAt,
      message: "Device registered after owner approval.",
    });
    return;
  }

  if (request.method === "GET" && route === "/api/devices") {
    if (!requireOperator(request, response)) return;
    sendJson(response, 200, { devices: devices.map(publicDevice) });
    return;
  }

  const detail = /^\/api\/devices\/([0-9a-f-]{36})$/.exec(route);
  if (request.method === "GET" && detail) {
    if (!requireOperator(request, response)) return;
    const device = devices.find((item) => item.id === detail[1]);
    if (!device) {
      sendJson(response, 404, { error: "Device not found." });
      return;
    }
    sendJson(response, 200, { device: publicDevice(device) });
    return;
  }

  if (request.method === "POST" && route === "/api/device/heartbeat") {
    const device = deviceForToken(bearerToken(request));
    if (!device) {
      sendJson(response, 401, { error: "Device session is invalid. Connect this Companion again." });
      return;
    }
    const body = await readBody(request);
    const metadata = cleanMetadata(body.device);
    device.lastSeenAt = new Date().toISOString();
    for (const [key, value] of Object.entries(metadata)) {
      if (value !== "" && value !== null) device[key] = value;
    }
    await saveJson(DATA_FILE, devices);
    sendJson(response, 200, { ok: true, deviceId: device.id, lastSeenAt: device.lastSeenAt });
    return;
  }

  if (request.method === "POST" && route === "/api/device/unpair") {
    const device = deviceForToken(bearerToken(request));
    if (!device) {
      sendJson(response, 401, { error: "Device session is invalid." });
      return;
    }
    devices = devices.filter((item) => item.id !== device.id);
    await saveJson(DATA_FILE, devices);
    sendJson(response, 200, { ok: true, message: "Device removed from Control inventory." });
    return;
  }

  if (request.method === "GET" && await serveControlApp(route, response)) return;
  sendJson(response, 404, { error: "Route not found." });
}

const server = http.createServer((request, response) => {
  handle(request, response).catch((error) => {
    const status = Number.isInteger(error.statusCode) ? error.statusCode : 500;
    if (status === 500) console.error("Request failed:", error.message);
    if (!response.headersSent) {
      sendJson(response, status, { error: status === 500 ? "Internal server error." : error.message });
    } else {
      response.destroy();
    }
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log("SkySpy API listening on port " + PORT);
  console.log("Data directory: " + DATA_DIR);
});
