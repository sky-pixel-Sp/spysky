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

type AuthStatus = "loading" | "setup" | "login" | "authenticated";
type ApiError = { error?: string };

const DEFAULT_API = window.location.origin;

function formatDate(value: string) {
  if (!value) return "Not reported";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Not reported" : date.toLocaleString();
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "An unexpected error occurred.";
}

export default function App() {
  const [apiBase, setApiBase] = useState(localStorage.getItem("skyspy.apiBase") ?? DEFAULT_API);
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [authStatus, setAuthStatus] = useState<AuthStatus>("loading");
  const [sessionToken, setSessionToken] = useState("");
  const [localMode, setLocalMode] = useState(false);
  const [devices, setDevices] = useState<Device[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [lastRefresh, setLastRefresh] = useState("");

  const normalizedApi = useMemo(() => apiBase.trim().replace(/\/+$/, ""), [apiBase]);
  const selectedDevice = devices.find((device) => device.id === selectedId) ?? null;
  const onlineCount = devices.filter((device) => device.status === "online").length;
  const isSetup = authStatus === "setup";

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    if (!normalizedApi) throw new Error("Enter the API server address.");
    const headers = new Headers(init.headers);
    headers.set("Content-Type", "application/json");
    if (sessionToken) headers.set("Authorization", "Bearer " + sessionToken);
    const response = await fetch(normalizedApi + path, { ...init, headers });
    const payload = (await response.json().catch(() => ({}))) as T & ApiError;
    if (!response.ok) throw new Error(payload.error || "Request failed with HTTP " + response.status + ".");
    return payload;
  }, [normalizedApi, sessionToken]);

  const refreshDevices = useCallback(async () => {
    const result = await request<{ devices: Device[] }>("/api/devices");
    setDevices(result.devices);
    setLastRefresh(new Date().toISOString());
    setSelectedId((current) => current && result.devices.some((device) => device.id === current)
      ? current
      : result.devices[0]?.id ?? null);
  }, [request]);

  useEffect(() => {
    let active = true;
    const boot = async () => {
      setAuthStatus("loading");
      setError("");
      try {
        const statusResponse = await fetch(normalizedApi + "/api/auth/status", { cache: "no-store" });
        const status = await statusResponse.json() as { configured?: boolean; localAccess?: boolean; error?: string };
        if (!statusResponse.ok) throw new Error(status.error || "Could not reach SkySpy server.");
        if (!active) return;
        localStorage.setItem("skyspy.apiBase", normalizedApi);

        // No operator password is requested when the browser is running on
        // the SkySpy server computer itself (loopback only).
        if (status.localAccess) {
          const localResponse = await fetch(normalizedApi + "/api/devices", { cache: "no-store" });
          if (!localResponse.ok) throw new Error("Local Control access could not load the device inventory.");
          const data = await localResponse.json() as { devices: Device[] };
          if (!active) return;
          sessionStorage.removeItem("skyspy.session");
          setSessionToken("");
          setLocalMode(true);
          setDevices(data.devices);
          setSelectedId(data.devices[0]?.id ?? null);
          setLastRefresh(new Date().toISOString());
          setAuthStatus("authenticated");
          return;
        }

        setLocalMode(false);
        const stored = sessionStorage.getItem("skyspy.session") ?? "";
        if (stored) {
          const response = await fetch(normalizedApi + "/api/devices", {
            headers: { Authorization: "Bearer " + stored },
            cache: "no-store"
          });
          if (response.ok) {
            const data = await response.json() as { devices: Device[] };
            if (!active) return;
            setSessionToken(stored);
            setLocalMode(false);
            setDevices(data.devices);
            setSelectedId(data.devices[0]?.id ?? null);
            setLastRefresh(new Date().toISOString());
            setAuthStatus("authenticated");
            return;
          }
          sessionStorage.removeItem("skyspy.session");
        }
        setAuthStatus(status.configured ? "login" : "setup");
      } catch (reason) {
        if (active) {
          setAuthStatus("login");
          setError(errorMessage(reason));
        }
      }
    };
    void boot();
    return () => { active = false; };
  }, [normalizedApi]);

  const authenticate = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    setNotice("");
    if (isSetup && password !== confirmPassword) {
      setError("The password confirmation does not match.");
      return;
    }
    setBusy(true);
    try {
      const result = await request<{ token: string }>(
        isSetup ? "/api/auth/setup" : "/api/auth/login",
        { method: "POST", body: JSON.stringify({ password }) }
      );
      setSessionToken(result.token);
      setLocalMode(false);
      sessionStorage.setItem("skyspy.session", result.token);
      setPassword("");
      setConfirmPassword("");
      const deviceResponse = await fetch(normalizedApi + "/api/devices", {
        headers: { Authorization: "Bearer " + result.token },
        cache: "no-store"
      });
      if (!deviceResponse.ok) throw new Error("Signed in, but device inventory could not be loaded.");
      const data = await deviceResponse.json() as { devices: Device[] };
      setDevices(data.devices);
      setSelectedId(data.devices[0]?.id ?? null);
      setLastRefresh(new Date().toISOString());
      setAuthStatus("authenticated");
      setNotice(isSetup ? "Admin account created. SkySpy Control is ready." : "Signed in to SkySpy Control.");
    } catch (reason) {
      setError(errorMessage(reason));
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
      setError(errorMessage(reason));
      if (errorMessage(reason).includes("Sign in")) {
        sessionStorage.removeItem("skyspy.session");
        setSessionToken("");
        setLocalMode(false);
        setAuthStatus("login");
      }
    } finally {
      setBusy(false);
    }
  };

  const signOut = async () => {
    try {
      await request("/api/auth/logout", { method: "POST", body: "{}" });
    } catch {
      // Clear the local session even if the server is unreachable.
    }
    sessionStorage.removeItem("skyspy.session");
    setSessionToken("");
    setLocalMode(false);
    setDevices([]);
    setSelectedId(null);
    setAuthStatus("login");
  };

  useEffect(() => {
    if (authStatus !== "authenticated") return;
    const timer = window.setInterval(() => {
      refreshDevices().catch((reason: unknown) => setError(errorMessage(reason)));
    }, 6000);
    return () => window.clearInterval(timer);
  }, [authStatus, refreshDevices]);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">S</div>
          <div><strong>SkySpy</strong><span>LAN DEVICE MANAGER</span></div>
        </div>
        <div className="sidebar-section-label">WORKSPACE</div>
        <button className="nav-item active" type="button"><span className="nav-icon">▦</span> Devices</button>
        <button className="nav-item" type="button" onClick={() => setNotice("Activity history is planned for a later milestone.")}><span className="nav-icon">◷</span> Activity<span className="nav-soon">SOON</span></button>
        <div className="sidebar-bottom">
          <div className="security-note"><span className="security-dot" /><div><strong>Owner-approved enrollment</strong><small>No pairing code required</small></div></div>
          <div className="sidebar-version">SKYSPY CONTROL · 0.2.2</div>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div><div className="eyebrow">DEVICE OPERATIONS</div><h1>Devices</h1></div>
          <div className="topbar-actions">
            <span className="connection-pill"><span className={authStatus === "authenticated" ? "pulse-dot" : "muted-dot"} />{authStatus === "authenticated" ? "API connected" : "Not connected"}</span>
            {authStatus === "authenticated" && <button className="button button-secondary" type="button" onClick={refresh} disabled={busy}>↻ Refresh</button>}
          </div>
        </header>

        {authStatus !== "authenticated" ? (
          <section className="connect-card">
            <div className="card-icon">⌁</div>
            <div className="eyebrow">{isSetup ? "FIRST-TIME SETUP" : "CONTROL SIGN-IN"}</div>
            <h2>{isSetup ? "Create your admin password" : "Sign in to SkySpy Control"}</h2>
            <p>{isSetup
              ? "Create the first admin account from the computer running the SkySpy server. You will not need to configure environment variables or paste an API token."
              : "Connect to your SkySpy server. For remote access, use an HTTPS server address."
            }</p>
            <form onSubmit={authenticate} className="connect-form">
              <label>API server URL
                <input value={apiBase} onChange={(event) => setApiBase(event.target.value)} onBlur={() => { if (apiBase.trim()) localStorage.setItem("skyspy.apiBase", normalizedApi); }} placeholder="http://localhost:8787" autoComplete="url" required />
              </label>
              <label>{isSetup ? "Admin password (12+ characters)" : "Admin password"}
                <input value={password} onChange={(event) => setPassword(event.target.value)} placeholder="Enter admin password" type="password" autoComplete={isSetup ? "new-password" : "current-password"} minLength={isSetup ? 12 : undefined} required />
              </label>
              {isSetup && <label>Confirm admin password
                <input value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} placeholder="Repeat admin password" type="password" autoComplete="new-password" minLength={12} required />
              </label>}
              <button className="button button-primary" type="submit" disabled={busy || password.length < (isSetup ? 12 : 1) || (isSetup && confirmPassword.length < 12)}>{busy ? "Please wait…" : isSetup ? "Create admin account" : "Sign in"}</button>
              <small className="form-hint">{isSetup ? "First admin setup is restricted to the server computer. After setup, sign in from other devices on your network." : "Your session is stored only in this browser tab."}</small>
            </form>
          </section>
        ) : (
          <>
            <section className="stats-grid">
              <div className="stat-card"><span className="stat-label">REGISTERED DEVICES</span><strong>{devices.length}</strong><small>Owner-approved Companion installs</small></div>
              <div className="stat-card"><span className="stat-label">ONLINE NOW</span><strong>{onlineCount}</strong><small>Heartbeat received recently</small></div>
              <div className="stat-card"><span className="stat-label">OFFLINE</span><strong>{devices.length - onlineCount}</strong><small>Waiting for Companion heartbeat</small></div>
            </section>
            <section className="content-grid">
              <div className="panel devices-panel">
                <div className="panel-heading">
                  <div><div className="eyebrow">INVENTORY</div><h2>Connected devices</h2></div>
                  <button className="button button-secondary" type="button" onClick={() => setNotice("Open SkySpy Companion, enter this server address, approve diagnostics sharing, then tap Connect This Device. No pairing code is needed.")}>＋ Connect a device</button>
                </div>
                {devices.length === 0 ? (
                  <div className="empty-state">
                    <div className="empty-icon">▣</div>
                    <h3>No devices connected yet</h3>
                    <p>Open SkySpy Companion on the device owner’s phone. Enter the server address, review the diagnostics list, enable consent, and tap Connect This Device. The device will appear here automatically.</p>
                    <button className="button button-primary" onClick={() => setNotice("Server address for Companion: " + normalizedApi + ". No pairing code or operator token is needed on the Companion.")} type="button">Show server address</button>
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
                <div className="panel-footer">Auto-refresh every 6 seconds · Last updated {lastRefresh ? formatDate(lastRefresh) : "—"}</div>
              </div>

              <aside className="details-column">
                <div className="panel enrollment-panel">
                  <div className="eyebrow">SIMPLE ENROLLMENT</div>
                  <h2>No pairing code</h2>
                  <p>Companion owners connect themselves. Each device appears after the owner approves the diagnostics it shares.</p>
                  <div className="server-address"><span>SERVER ADDRESS</span><code>{normalizedApi}</code></div>
                </div>
                <div className="panel detail-panel">
                  <div className="eyebrow">DEVICE DETAILS</div>
                  {selectedDevice ? (
                    <>
                      <div className="detail-title"><div className="device-avatar large">▯</div><div><h2>{selectedDevice.name || selectedDevice.model}</h2><span className={selectedDevice.status === "online" ? "status-text online-text" : "status-text"}>{selectedDevice.status}</span></div></div>
                      <div className="detail-group"><span>Manufacturer</span><strong>{selectedDevice.manufacturer || "Not reported"}</strong></div>
                      <div className="detail-group"><span>Model</span><strong>{selectedDevice.model}</strong></div>
                      <div className="detail-group"><span>Android version</span><strong>{selectedDevice.androidVersion || "Not reported"}</strong></div>
                      <div className="detail-group"><span>Security patch</span><strong>{selectedDevice.securityPatch || "Not reported"}</strong></div>
                      <div className="detail-group"><span>Battery</span><strong>{selectedDevice.batteryPercent === null ? "Not reported" : selectedDevice.batteryPercent + "%" + (selectedDevice.charging ? " · Charging" : "")}</strong></div>
                      <div className="detail-group"><span>Last heartbeat</span><strong>{formatDate(selectedDevice.lastSeenAt)}</strong></div>
                      <div className="detail-group id-row"><span>Device ID</span><code>{selectedDevice.id}</code></div>
                      <div className="owner-note"><strong>Owner permission required</strong><p>This build provides inventory and diagnostics. Screen sharing and file transfer are not available in this milestone.</p></div>
                    </>
                  ) : <div className="detail-empty"><div className="empty-icon">⌖</div><h3>Select a device</h3><p>Choose a connected device to view its reported details.</p></div>}
                </div>
              </aside>
            </section>
            <div className="session-actions">
              {localMode
                ? <span>Local-only mode · no operator password required on this computer</span>
                : <><span>Signed in securely</span><button className="button button-secondary" onClick={signOut} type="button">Sign out</button></>}
            </div>
          </>
        )}

        {error && <div role="alert" className="toast error-toast">{error}</div>}
        {notice && <div role="status" className="toast notice-toast">{notice}<button onClick={() => setNotice("")} type="button" aria-label="Dismiss">×</button></div>}
        <footer className="page-footer"><span>SkySpy LAN · Owner-approved device management</span><span>Control build 0.2.2</span></footer>
      </main>
    </div>
  );
}
