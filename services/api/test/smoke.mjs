import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes, randomInt } from "node:crypto";

const currentDir = path.dirname(fileURLToPath(import.meta.url));
const serverFile = path.resolve(currentDir, "../src/server.mjs");
const dataDir = await mkdtemp(path.join(tmpdir(), "skyspy-api-test-"));
const port = 18_000 + randomInt(10_000);
const token = randomBytes(32).toString("base64url");
const baseUrl = `http://127.0.0.1:${port}`;

const child = spawn(process.execPath, [serverFile], {
  env: {
    ...process.env,
    SKYSPY_PORT: String(port),
    SKYSPY_DATA_DIR: dataDir,
    SKYSPY_ADMIN_TOKEN: token,
    SKYSPY_ALLOWED_ORIGIN: "http://localhost:5173"
  },
  stdio: "ignore"
});

async function waitForServer() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    if (child.exitCode !== null) {
      throw new Error("SkySpy API exited before becoming healthy.");
    }
    try {
      const response = await fetch(`${baseUrl}/health`);
      if (response.ok) return;
    } catch {
      // The local server may still be starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 150));
  }
  throw new Error("SkySpy API did not become healthy in time.");
}

async function request(route, options = {}) {
  const response = await fetch(`${baseUrl}${route}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers ?? {})
    }
  });
  return { status: response.status, body: await response.json() };
}

try {
  await waitForServer();

  const unauthorized = await request("/api/devices");
  assert.equal(unauthorized.status, 401, "device inventory must require operator auth");

  const pairing = await request("/api/pairing", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: "{}"
  });
  assert.equal(pairing.status, 201);
  assert.match(pairing.body.code, /^\d{6}$/);

  const invalidClaim = await request("/api/pairing/claim", {
    method: "POST",
    body: JSON.stringify({
      code: "999999",
      device: { model: "Test handset" }
    })
  });
  assert.equal(invalidClaim.status, 400);

  const claim = await request("/api/pairing/claim", {
    method: "POST",
    body: JSON.stringify({
      code: pairing.body.code,
      device: {
        manufacturer: "Sky Labs",
        model: "Test handset",
        androidVersion: "Android test",
        securityPatch: "2026-10-01",
        batteryPercent: 84,
        charging: false,
        appVersion: "0.1.0"
      }
    })
  });
  assert.equal(claim.status, 201);
  assert.ok(claim.body.deviceId);
  assert.ok(claim.body.deviceToken);

  const inventory = await request("/api/devices", {
    headers: { Authorization: `Bearer ${token}` }
  });
  assert.equal(inventory.status, 200);
  assert.equal(inventory.body.devices.length, 1);
  assert.equal(inventory.body.devices[0].model, "Test handset");
  assert.equal(inventory.body.devices[0].status, "online");
  assert.equal("tokenHash" in inventory.body.devices[0], false);

  const heartbeat = await request("/api/device/heartbeat", {
    method: "POST",
    headers: { Authorization: `Bearer ${claim.body.deviceToken}` },
    body: JSON.stringify({ device: { batteryPercent: 80, charging: true } })
  });
  assert.equal(heartbeat.status, 200);

  const reusedCode = await request("/api/pairing/claim", {
    method: "POST",
    body: JSON.stringify({
      code: pairing.body.code,
      device: { model: "Second handset" }
    })
  });
  assert.equal(reusedCode.status, 400, "pairing codes must be single-use");

  const unpair = await request("/api/device/unpair", {
    method: "POST",
    headers: { Authorization: `Bearer ${claim.body.deviceToken}` },
    body: "{}"
  });
  assert.equal(unpair.status, 200);

  const wrongToken = await request("/api/device/heartbeat", {
    method: "POST",
    headers: { Authorization: "Bearer invalid-token" },
    body: JSON.stringify({ device: {} })
  });
  assert.equal(wrongToken.status, 401);

  const emptyInventory = await request("/api/devices", {
    headers: { Authorization: `Bearer ${token}` }
  });
  assert.equal(emptyInventory.status, 200);
  assert.equal(emptyInventory.body.devices.length, 0, "unpaired device must leave inventory");

  console.log("PASS: operator auth, one-time pairing, inventory, device token isolation, heartbeat, unpair.");
} finally {
  child.kill("SIGTERM");
  await rm(dataDir, { recursive: true, force: true });
}
