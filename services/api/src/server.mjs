import http from "node:http";
import path from "node:path";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import {
  createHash,
  randomBytes,
  randomInt,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";

const PORT = Number.parseInt(process.env.SKYSPY_PORT ?? "8787", 10);
const DATA_DIR = path.resolve(process.env.SKYSPY_DATA_DIR ?? "./data");
const DATA_FILE = path.join(DATA_DIR, "devices.json");
const ADMIN_TOKEN = process.env.SKYSPY_ADMIN_TOKEN ?? "";
const ALLOWED_ORIGIN = process.env.SKYSPY_ALLOWED_ORIGIN ?? "http://localhost:5173";
const PAIRING_TTL_MS = 3 * 60 * 1000;
const ONLINE_WINDOW_MS = 90 * 1000;
const MAX_BODY_BYTES = 16 * 1024;

if (!Number.isInteger(PORT) || PORT < 1 || PORT > 65535) {
  throw new Error("SKYSPY_PORT must be a valid TCP port.");
}
if (ADMIN_TOKEN.length < 32) {
  throw new Error("SKYSPY_ADMIN_TOKEN must contain at least 32 characters.");
}

const pairingCodes = new Map();
const claimAttempts = new Map();

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function safeEqual(left, right) {
  const a = Buffer.from(String(left));
  const b = Buffer.from(String(right));
  return a.length === b.length && timingSafeEqual(a, b);
}

function bearerToken(request) {
  const header = request.headers.authorization ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(header);
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

function setCors(request, response) {
  const origin = request.headers.origin;
  if (origin && origin !== ALLOWED_ORIGIN) {
    sendJson(response, 403, { error: "Origin is not allowed." });
    return false;
  }
  if (origin) {
    response.setHeader("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
    response.setHeader("Vary", "Origin");
    response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
    response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  }
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

function cleanBattery(value) {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  return Math.max(0, Math.min(100, Math.round(value)));
}

function cleanBoolean(value) {
  return typeof value === "boolean" ? value : null;
}

function cleanMetadata(input) {
  return {
    manufacturer: cleanText(input.manufacturer, 50),
    model: cleanText(input.model, 80),
    androidVersion: cleanText(input.androidVersion, 30),
    securityPatch: cleanText(input.securityPatch, 20),
    batteryPercent: cleanBattery(input.batteryPercent),
    charging: cleanBoolean(input.charging),
    appVersion: cleanText(input.appVersion, 30),
  };
}

async function loadDevices() {
  await mkdir(DATA_DIR, { recursive: true });
  try {
    const parsed = JSON.parse(await readFile(DATA_FILE, "utf8"));
    return Array.isArray(parsed.devices) ? parsed.devices : [];
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    return [];
  }
}

let devices = await loadDevices();

async function persistDevices() {
  const temporary = `${DATA_FILE}.tmp`;
  await writeFile(temporary, JSON.stringify({ devices }, null, 2), { mode: 0o600 });
  await rename(temporary, DATA_FILE);
}

function requireOperator(request, response) {
  const supplied = bearerToken(request);
  if (!supplied || !safeEqual(supplied, ADMIN_TOKEN)) {
    sendJson(response, 401, { error: "Operator authentication required." });
    return false;
  }
  return true;
}

function publicDevice(device) {
  const { tokenHash, ...safe } = device;
  return {
    ...safe,
    status: Date.now() - Date.parse(device.lastSeenAt) <= ONLINE_WINDOW_MS ? "online" : "offline",
  };
}

function getDeviceByToken(token) {
  if (!token) return null;
  const digest = sha256(token);
  return devices.find((device) => safeEqual(device.tokenHash, digest)) ?? null;
}

function clientAddress(request) {
  return request.socket.remoteAddress ?? "unknown";
}

function isRateLimited(address) {
  const now = Date.now();
  const bucket = claimAttempts.get(address) ?? { count: 0, resetAt: now + 60_000 };
  if (now > bucket.resetAt) {
    bucket.count = 0;
    bucket.resetAt = now + 60_000;
  }
  bucket.count += 1;
  claimAttempts.set(address, bucket);
  return bucket.count > 12;
}

function pruneEphemeralState() {
  const now = Date.now();
  for (const [code, session] of pairingCodes) {
    if (session.expiresAt <= now || session.used) pairingCodes.delete(code);
  }
  for (const [address, bucket] of claimAttempts) {
    if (bucket.resetAt <= now) claimAttempts.delete(address);
  }
}

function makePairingCode() {
  pruneEphemeralState();
  let code;
  do {
    code = String(randomInt(0, 1_000_000)).padStart(6, "0");
  } while (pairingCodes.has(code));
  return code;
}

async function route(request, response) {
  if (!setCors(request, response)) return;
  if (request.method === "OPTIONS") {
    response.writeHead(204);
    response.end();
    return;
  }

  const url = new URL(request.url ?? "/", "http://localhost");
  const pathname = url.pathname;

  if (request.method === "GET" && pathname === "/health") {
    sendJson(response, 200, { ok: true, service: "skyspy-api", version: "0.1.0" });
    return;
  }

  if (request.method === "POST" && pathname === "/api/pairing") {
    if (!requireOperator(request, response)) return;
    const code = makePairingCode();
    const expiresAt = Date.now() + PAIRING_TTL_MS;
    pairingCodes.set(code, { expiresAt, used: false });
    sendJson(response, 201, {
      code,
      expiresAt: new Date(expiresAt).toISOString(),
      expiresInSeconds: Math.floor(PAIRING_TTL_MS / 1000),
    });
    return;
  }

  if (request.method === "POST" && pathname === "/api/pairing/claim") {
    const address = clientAddress(request);
    if (isRateLimited(address)) {
      sendJson(response, 429, { error: "Too many pairing attempts. Try again later." });
      return;
    }
    const body = await readBody(request);
    const code = typeof body.code === "string" ? body.code.trim() : "";
    const session = pairingCodes.get(code);
    if (!/^\d{6}$/.test(code) || !session || session.used || session.expiresAt <= Date.now()) {
      sendJson(response, 400, { error: "Pairing code is invalid or expired." });
      return;
    }

    const metadata = cleanMetadata(body.device ?? {});
    if (!metadata.model) {
      sendJson(response, 400, { error: "Device model is required." });
      return;
    }

    session.used = true;
    const deviceToken = randomBytes(32).toString("base64url");
    const device = {
      id: randomUUID(),
      name: cleanText(body.deviceName, 60) || metadata.model,
      ...metadata,
      tokenHash: sha256(deviceToken),
      createdAt: new Date().toISOString(),
      lastSeenAt: new Date().toISOString(),
    };
    devices.push(device);
    await persistDevices();

    sendJson(response, 201, {
      deviceId: device.id,
      deviceToken,
      pairedAt: device.createdAt,
      message: "Device paired. Keep the Companion installed and open to send status heartbeats.",
    });
    return;
  }

  if (request.method === "GET" && pathname === "/api/devices") {
    if (!requireOperator(request, response)) return;
    sendJson(response, 200, { devices: devices.map(publicDevice) });
    return;
  }

  const detailMatch = /^\/api\/devices\/([0-9a-f-]{36})$/.exec(pathname);
  if (request.method === "GET" && detailMatch) {
    if (!requireOperator(request, response)) return;
    const device = devices.find((item) => item.id === detailMatch[1]);
    if (!device) {
      sendJson(response, 404, { error: "Device not found." });
      return;
    }
    sendJson(response, 200, { device: publicDevice(device) });
    return;
  }

  if (request.method === "POST" && pathname === "/api/device/heartbeat") {
    const device = getDeviceByToken(bearerToken(request));
    if (!device) {
      sendJson(response, 401, { error: "Device authentication failed. Pair this Companion again." });
      return;
    }
    const body = await readBody(request);
    const metadata = cleanMetadata(body.device ?? {});
    device.lastSeenAt = new Date().toISOString();
    for (const [key, value] of Object.entries(metadata)) {
      if (value !== "" && value !== null) device[key] = value;
    }
    await persistDevices();
    sendJson(response, 200, { ok: true, deviceId: device.id, lastSeenAt: device.lastSeenAt });
    return;
  }

  sendJson(response, 404, { error: "Route not found." });
}

const server = http.createServer((request, response) => {
  route(request, response).catch((error) => {
    const status = Number.isInteger(error.statusCode) ? error.statusCode : 500;
    if (status === 500) {
      console.error("Request failed:", error.message);
    }
    if (!response.headersSent) {
      sendJson(response, status, { error: status === 500 ? "Internal server error." : error.message });
    } else {
      response.destroy();
    }
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`SkySpy API listening on 0.0.0.0:${PORT}`);
});
