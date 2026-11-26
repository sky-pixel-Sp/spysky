import { useCallback, useEffect, useMemo, useState } from "react";

type Device = {
  id: string;
  name: string;
  manufacturer: string;
  model: string;
  androidVersion: string;
  securityPatch: string;
  batteryPercent: number | null;
  charging: boolean | null;
  appVersion: string;
  createdAt: string;
  lastSeenAt: string;
  status: "online" | "offline";
};

type Pairing = {
  code: string;
  expiresAt: string;
  expiresInSeconds: number;
};

type ApiError = { error?: string };

const initialApi = localStorage.getItem("skyspy.apiBase") ?? "http://localhost:8787";

function formatDate(value: string) {
  if (!value) return "Not reported";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Not reported" : date.toLocaleString();
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "An unexpected error occurred.";
}

export default function App() {
  const [apiBase, setApiBase] = useState(initialApi);
  const [operatorToken, setOperatorToken] = useState("");
  const [authenticated, setAuthenticated] = useState(false);
  const [devices, setDevices] = useState<Device[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [pairing, setPairing] = useState<Pairing | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [lastRefresh, setLastRefresh] = useState("");

  const normalizedApi = useMemo(() => apiBase.trim().replace(/\/+$/, ""), [apiBase]);
  const selectedDevice = devices.find((device) => device.id === selectedId) ?? null;
  const onlineCount = devices.filter((device) => device.status === "online").length;

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    if (!normalizedApi) throw new Error("Enter the API server address.");
    const headers = new Headers(init.headers);
    headers.set("Content-Type", "application/json");
    if (operatorToken) headers.set("Authorization", `Bearer ${operatorToken}`);
    const response = await fetch(`${normalizedApi}${path}`, { ...init, headers });
    const payload = (await response.json().catch(() => ({}))) as T & ApiError;
    if (!response.ok) {
      throw new Error(payload.error || `Request failed with HTTP ${response.status}.`);
    }
    return payload;
  }, [normalizedApi, operatorToken]);

  const refreshDevices = useCallback(async () => {
    const result = await request<{ devices: Device[] }>("/api/devices");
    setDevices(result.devices);
    setLastRefresh(new Date().toISOString());
    setSelectedId((current) => current && result.devices.some((device) => device.id === current)
      ? current
      : result.devices[0]?.id ?? null);
  }, [request]);

  const connect = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const result = await request<{ devices: Device[] }>("/api/devices");
      setDevices(result.devices);
      setAuthenticated(true);
      localStorage.setItem("skyspy.apiBase", normalizedApi);
      setLastRefresh(new Date().toISOString());
      setSelectedId(result.devices[0]?.id ?? null);
      setNotice("Control connected. Only paired devices are shown.");
    } catch (reason) {
      setAuthenticated(false);
      setError(getErrorMessage(reason));
    } finally {
      setBusy(false);
    }
  };

  const createPairing = async () => {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const result = await request<Pairing>("/api/pairing", { method: "POST", body: "{}" });
      setPairing(result);
      setNotice("Pairing code created. Enter it in the owner's Companion app.");
    } catch (reason) {
      setError(getErrorMessage(reason));
    } finally {
      setBusy(false);
    }
  };

  const refresh = async () => {
    setBusy(true);
    setError("");
    try {
      await refreshDevices();
    } catch (reason) {
      setError(getErrorMessage(reason));
      if (getErrorMessage(reason).includes("authentication")) setAuthenticated(false);
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    if (!authenticated) return;
    const timer = window.setInterval(() => {
      refreshDevices().catch((reason: unknown) => setError(getErrorMessage(reason)));
    }, 15000);
    return () => window.clearInterval(timer);
  }, [authenticated, refreshDevices]);

  const isPairingExpired = pairing ? Date.now() >= Date.parse(pairing.expiresAt) : false;

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">S</div>
          <div>
            <strong>SkySpy</strong>
            <span>LAN DEVICE MANAGER</span>
          </div>
        </div>
        <div className="sidebar-section-label">WORKSPACE</div>
        <button className="nav-item active" type="button">
          <span className="nav-icon">▦</span> Devices
        </button>
        <button className="nav-item" type="button" onClick={() => setNotice("Audit history is planned for a later milestone.")}>
          <span className="nav-icon">◷</span> Activity
          <span className="nav-soon">SOON</span>
        </button>
        <div className="sidebar-bottom">
          <div className="security-note">
            <span className="security-dot" />
            <div><strong>Consent-first mode</strong><small>Owner-controlled pairing</small></div>
          </div>
          <div className="sidebar-version">SKYSPY CONTROL · 0.1.0</div>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div>
            <div className="eyebrow">DEVICE OPERATIONS</div>
            <h1>Devices</h1>
          </div>
          <div className="topbar-actions">
            <span className="connection-pill"><span className={authenticated ? "pulse-dot" : "muted-dot"} />{authenticated ? "API connected" : "Not connected"}</span>
            <button className="button button-secondary" type="button" onClick={refresh} disabled={!authenticated || busy}>↻ Refresh</button>
          </div>
        </header>

        {!authenticated ? (
          <section className="connect-card">
            <div className="card-icon">⌁</div>
            <div className="eyebrow">CONTROL CONNECTION</div>
            <h2>Connect to your SkySpy server</h2>
            <p>Use a private LAN address on the same trusted network, or an HTTPS endpoint for remote access.</p>
            <form onSubmit={connect} className="connect-form">
              <label>API server URL
                <input value={apiBase} onChange={(event) => setApiBase(event.target.value)} placeholder="https://skyspy.example.com" autoComplete="url" required />
              </label>
              <label>Operator token
                <input value={operatorToken} onChange={(event) => setOperatorToken(event.target.value)} placeholder="Paste the server operator token" type="password" autoComplete="current-password" required />
              </label>
              <button className="button button-primary" type="submit" disabled={busy || operatorToken.length < 32}>
                {busy ? "Connecting…" : "Connect to server"}
              </button>
              <small className="form-hint">The token stays in this tab's memory and is not embedded in the web build.</small>
            </form>
          </section>
        ) : (
          <>
            <section className="stats-grid">
              <div className="stat-card"><span className="stat-label">REGISTERED DEVICES</span><strong>{devices.length}</strong><small>Paired with this server</small></div>
              <div className="stat-card"><span className="stat-label">ONLINE NOW</span><strong>{onlineCount}</strong><small>Heartbeat seen recently</small></div>
              <div className="stat-card"><span className="stat-label">OFFLINE</span><strong>{devices.length - onlineCount}</strong><small>Awaiting next heartbeat</small></div>
            </section>

            <section className="content-grid">
              <div className="panel devices-panel">
                <div className="panel-heading">
                  <div><div className="eyebrow">INVENTORY</div><h2>Paired devices</h2></div>
                  <button className="button button-primary" type="button" onClick={createPairing} disabled={busy}>＋ Pair device</button>
                </div>
                {devices.length === 0 ? (
                  <div className="empty-state">
                    <div className="empty-icon">▣</div>
                    <h3>No devices paired yet</h3>
                    <p>Create a one-time pairing code. The device owner enters it in SkySpy Companion to register their device.</p>
                    <button className="button button-primary" onClick={createPairing} disabled={busy} type="button">Create pairing code</button>
                  </div>
                ) : (
                  <div className="device-list">
                    {devices.map((device) => (
                      <button className={selectedId === device.id ? "device-row selected" : "device-row"} key={device.id} onClick={() => setSelectedId(device.id)} type="button">
                        <span className="device-avatar">▯</span>
                        <span className="device-main"><strong>{device.name || device.model}</strong><small>{[device.manufacturer, device.model].filter(Boolean).join(" ") || "Android device"}</small></span>
                        <span className={device.status === "online" ? "status-badge online" : "status-badge offline"}><i />{device.status}</span>
                        <span className="row-chevron">›</span>
                      </button>
                    ))}
                  </div>
                )}
                <div className="panel-footer">Auto-refresh every 15 seconds · Last updated {lastRefresh ? formatDate(lastRefresh) : "—"}</div>
              </div>

              <aside className="details-column">
                {pairing && (
                  <div className="panel pairing-panel">
                    <div className="panel-heading compact"><div><div className="eyebrow">OWNER ENROLLMENT</div><h2>Pairing code</h2></div><button className="icon-button" onClick={() => setPairing(null)} type="button" aria-label="Close pairing code">×</button></div>
                    <div className="pair-code">{pairing.code}</div>
                    <p>{isPairingExpired ? "This code has expired. Generate another code." : "Open SkySpy Companion on the device and enter this code."}</p>
                    <div className="code-expiry">{isPairingExpired ? "EXPIRED" : `EXPIRES ${formatDate(pairing.expiresAt)}`}</div>
                    <button className="button button-secondary full-width" type="button" onClick={createPairing} disabled={busy}>Generate new code</button>
                  </div>
                )}
                <div className="panel detail-panel">
                  <div className="eyebrow">DEVICE DETAILS</div>
                  {selectedDevice ? (
                    <>
                      <div className="detail-title"><div className="device-avatar large">▯</div><div><h2>{selectedDevice.name || selectedDevice.model}</h2><span className={selectedDevice.status === "online" ? "status-text online-text" : "status-text"}>{selectedDevice.status}</span></div></div>
                      <div className="detail-group"><span>Manufacturer</span><strong>{selectedDevice.manufacturer || "Not reported"}</strong></div>
                      <div className="detail-group"><span>Model</span><strong>{selectedDevice.model}</strong></div>
                      <div className="detail-group"><span>Android version</span><strong>{selectedDevice.androidVersion || "Not reported"}</strong></div>
                      <div className="detail-group"><span>Security patch</span><strong>{selectedDevice.securityPatch || "Not reported"}</strong></div>
                      <div className="detail-group"><span>Battery</span><strong>{selectedDevice.batteryPercent === null ? "Not reported" : `${selectedDevice.batteryPercent}%${selectedDevice.charging ? " · Charging" : ""}`}</strong></div>
                      <div className="detail-group"><span>Last heartbeat</span><strong>{formatDate(selectedDevice.lastSeenAt)}</strong></div>
                      <div className="detail-group id-row"><span>Device ID</span><code>{selectedDevice.id}</code></div>
                      <div className="owner-note"><strong>Owner permission required</strong><p>This milestone provides inventory and diagnostics only. Screen sharing and file access are not available until the owner explicitly approves those separate sessions.</p></div>
                    </>
                  ) : (
                    <div className="detail-empty"><div className="empty-icon">⌖</div><h3>Select a device</h3><p>Choose a paired device from the list to view its reported details.</p></div>
                  )}
                </div>
              </aside>
            </section>
          </>
        )}

        {error && <div role="alert" className="toast error-toast">{error}</div>}
        {notice && <div role="status" className="toast notice-toast">{notice}<button onClick={() => setNotice("")} type="button" aria-label="Dismiss">×</button></div>}
        <footer className="page-footer"><span>SkySpy LAN · Consent-based device management</span><span>Control build 0.1.0</span></footer>
      </main>
    </div>
  );
}
