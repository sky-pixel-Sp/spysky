import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID, randomInt } from "node:crypto";

const currentDir = path.dirname(fileURLToPath(import.meta.url));
const serverFile = path.resolve(currentDir, "../src/server.mjs");
const dataDir = await mkdtemp(path.join(tmpdir(), "skyspy-api-test-"));
const publicDir = path.join(dataDir, "public");
await mkdir(publicDir, { recursive: true });
await writeFile(path.join(publicDir, "index.html"), "<!doctype html><title>SkySpy Test Control</title>");
const port = 18000 + randomInt(10000);
const baseUrl = "http://127.0.0.1:" + port;
const child = spawn(process.execPath, [
  serverFile,
  "--port", String(port),
  "--data-dir", dataDir,
  "--public-dir", publicDir
], { stdio: "ignore" });

async function waitForServer() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    if (child.exitCode !== null) throw new Error("SkySpy API exited before becoming healthy.");
    try {
      const response = await fetch(baseUrl + "/health");
      if (response.ok) return;
    } catch {
      // Server may still be starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 150));
  }
  throw new Error("SkySpy API did not become healthy in time.");
}

async function request(route, options = {}) {
  const response = await fetch(baseUrl + route, {
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

  const health = await request("/health");
  assert.equal(health.status, 200);
  assert.equal(health.body.ok, true);

  const controlPageResponse = await fetch(baseUrl + "/");
  assert.equal(controlPageResponse.status, 200);
  assert.match(await controlPageResponse.text(), /SkySpy Test Control/);

  const initialStatus = await request("/api/auth/status");
  assert.equal(initialStatus.status, 200);
  assert.equal(initialStatus.body.configured, false);

  const setup = await request("/api/auth/setup", {
    method: "POST",
    body: JSON.stringify({ password: "SkySpy-Admin-First-Run-2026!" })
  });
  assert.equal(setup.status, 201);
  assert.ok(setup.body.token);

  const secondSetup = await request("/api/auth/setup", {
    method: "POST",
    body: JSON.stringify({ password: "Another-Admin-Password-2026!" })
  });
  assert.equal(secondSetup.status, 409);

  const wrongLogin = await request("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ password: "incorrect-password" })
  });
  assert.equal(wrongLogin.status, 401);

  const login = await request("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ password: "SkySpy-Admin-First-Run-2026!" })
  });
  assert.equal(login.status, 200);
  assert.ok(login.body.token);

  const unauthenticatedInventory = await request("/api/devices");
  assert.equal(unauthenticatedInventory.status, 401);

  const installId = randomUUID();
  const diagnostics = {
    manufacturer: "Sky Labs",
    model: "Owner-approved test phone",
    androidVersion: "Android test",
    securityPatch: "2026-10-01",
    batteryPercent: 84,
    charging: false,
    appVersion: "0.2.0"
  };

  const noConsent = await request("/api/device/register", {
    method: "POST",
    body: JSON.stringify({ installId, consent: false, device: diagnostics })
  });
  assert.equal(noConsent.status, 403);

  const registration = await request("/api/device/register", {
    method: "POST",
    body: JSON.stringify({ installId, consent: true, device: diagnostics })
  });
  assert.equal(registration.status, 201);
  assert.ok(registration.body.deviceId);
  assert.ok(registration.body.deviceToken);

  const inventory = await request("/api/devices", {
    headers: { Authorization: "Bearer " + login.body.token }
  });
  assert.equal(inventory.status, 200);
  assert.equal(inventory.body.devices.length, 1);
  assert.equal(inventory.body.devices[0].model, diagnostics.model);
  assert.equal(inventory.body.devices[0].status, "online");
  assert.equal("tokenHash" in inventory.body.devices[0], false);
  assert.equal("installIdHash" in inventory.body.devices[0], false);

  const reRegistration = await request("/api/device/register", {
    method: "POST",
    body: JSON.stringify({ installId, consent: true, device: diagnostics })
  });
  assert.equal(reRegistration.status, 201);
  assert.equal(reRegistration.body.deviceId, registration.body.deviceId);
  assert.notEqual(reRegistration.body.deviceToken, registration.body.deviceToken);

  const heartbeat = await request("/api/device/heartbeat", {
    method: "POST",
    headers: { Authorization: "Bearer " + reRegistration.body.deviceToken },
    body: JSON.stringify({ device: { batteryPercent: 80, charging: true } })
  });
  assert.equal(heartbeat.status, 200);

  const unpair = await request("/api/device/unpair", {
    method: "POST",
    headers: { Authorization: "Bearer " + reRegistration.body.deviceToken },
    body: "{}"
  });
  assert.equal(unpair.status, 200);

  const emptyInventory = await request("/api/devices", {
    headers: { Authorization: "Bearer " + login.body.token }
  });
  assert.equal(emptyInventory.status, 200);
  assert.equal(emptyInventory.body.devices.length, 0);

  const logout = await request("/api/auth/logout", {
    method: "POST",
    headers: { Authorization: "Bearer " + login.body.token },
    body: "{}"
  });
  assert.equal(logout.status, 200);

  const afterLogout = await request("/api/devices", {
    headers: { Authorization: "Bearer " + login.body.token }
  });
  assert.equal(afterLogout.status, 401);

  console.log("PASS: default configuration, admin setup/login/logout, consent-gated no-code registration, inventory, token rotation, heartbeat and unpair.");
} finally {
  child.kill("SIGTERM");
  await rm(dataDir, { recursive: true, force: true });
}
