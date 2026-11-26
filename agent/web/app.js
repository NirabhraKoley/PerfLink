// PerfLink web app: the iPhone (and any browser) version of the PerfLink phone app.
// Served by the PC agent on its own port; it speaks the same WebSocket messages as the Android app.
"use strict";

// ====================================================================== helpers
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const num = (v) => (typeof v === "number" && isFinite(v) ? v : null);
const f0 = (v) => (num(v) == null ? "N/A" : Math.round(v).toString());
const f1 = (v) => (num(v) == null ? "N/A" : v.toFixed(1));
const pct = (v) => (num(v) == null ? "N/A" : `${f0(v)} %`);
const temp = (v) => (num(v) == null ? "N/A" : `${f0(v)} °C`);
const gbOf = (mb) => (mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${f0(mb)} MB`);
const C = {
  text: "#E8EDF3", dim: "#8A96A6", accent: "#4FC3F7", good: "#5BD68A", warn: "#F2C14E", bad: "#FF6B6B",
  gpu: "#FF7A59", cpu: "#7C9CFF", down: "#4FC3F7", up: "#B388FF",
  hCpu: "#2DD4BF", hGpu: "#A3E635", hRam: "#A78BFA", hNet: "#F59E0B",
};
const fpsColor = (v) => (num(v) == null ? C.dim : v >= 60 ? C.good : v >= 30 ? C.warn : C.bad);
const tempColor = (v) => (num(v) == null ? C.dim : v >= 85 ? C.bad : v >= 70 ? C.warn : C.text);
const pingColor = (v) => (num(v) == null ? C.dim : v <= 40 ? C.good : v <= 90 ? C.warn : C.bad);
function bps(v) {
  if (num(v) == null) return "N/A";
  if (v >= 1e9) return `${(v / 1e9).toFixed(2)} Gbps`;
  if (v >= 1e6) return `${(v / 1e6).toFixed(1)} Mbps`;
  if (v >= 1e3) return `${(v / 1e3).toFixed(0)} Kbps`;
  return `${v.toFixed(0)} bps`;
}
/** bits/s -> [value, unit] in bytes/s, like Task Manager */
function byteRate(v) {
  if (num(v) == null) return ["–", "KB/s"];
  const b = v / 8;
  if (b >= 1e6) return [(b / 1e6).toFixed(1), "MB/s"];
  if (b >= 1e3) return [(b / 1e3).toFixed(0), "KB/s"];
  return [b.toFixed(0), "B/s"];
}
function mmss(sec) {
  if (num(sec) == null) return "0:00";
  const t = Math.floor(sec), p = (n) => String(n).padStart(2, "0");
  return t >= 3600 ? `${Math.floor(t / 3600)}:${p(Math.floor(t / 60) % 60)}:${p(t % 60)}` : `${Math.floor(t / 60)}:${p(t % 60)}`;
}
const shortCpu = (n) => (n || "").replace("(R)", "").replace("(TM)", "").replace("12th Gen ", "").replace("CPU", "").replace(/\s+/g, " ").trim();
function weatherEmoji(code, day) {
  if (code == null) return "🌡️";
  if (code === 0) return day ? "☀️" : "🌙";
  if (code === 1 || code === 2) return day ? "🌤️" : "☁️";
  if (code === 3) return "☁️";
  if (code === 45 || code === 48) return "🌫️";
  if (code >= 51 && code <= 57) return "🌦️";
  if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️";
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) return "❄️";
  if (code >= 95 && code <= 99) return "⛈️";
  return "🌡️";
}
const ago = (s) => (s < 60 ? "now" : s < 3600 ? `${Math.floor(s / 60)}m` : s < 86400 ? `${Math.floor(s / 3600)}h` : `${Math.floor(s / 86400)}d`);
const store = {
  get(k, d) { try { const v = localStorage.getItem("perflink_" + k); return v == null ? d : v; } catch { return d; } },
  set(k, v) { try { localStorage.setItem("perflink_" + k, v); } catch { /* private mode */ } },
};

// ====================================================================== this phone's identity (for the PC's Allow / Block)
function makeId() {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);   // works on plain http (crypto.randomUUID needs https)
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
const DEVICE_ID = store.get("device_id", null) || (() => { const id = makeId(); store.set("device_id", id); return id; })();
const STANDALONE = window.navigator.standalone === true || matchMedia("(display-mode: standalone)").matches
  || matchMedia("(display-mode: fullscreen)").matches;
function deviceName() {
  const ua = navigator.userAgent;
  const ipad = /iPad/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
  const kind = ipad ? "iPad" : /iPhone|iPod/.test(ua) ? "iPhone" : /Android/.test(ua) ? "Android browser" : "Web browser";
  // a Home Screen app keeps its own storage (so its own ID): say which one the PC is asked about
  const browser = /Edg/.test(ua) ? "Edge" : /CriOS|Chrome/.test(ua) ? "Chrome" : /FxiOS|Firefox/.test(ua) ? "Firefox"
    : /Safari/.test(ua) ? "Safari" : "browser";
  return `${kind} (${STANDALONE ? "Home Screen app" : browser})`;
}
const DEVICE_NAME = deviceName();

// ====================================================================== connection
const HISTORY = 120;
const S = {
  conn: "connecting",     // connecting | live | reconnecting | pending | refused | off
  stats: null, refused: null, pcName: null, error: null,
  art: null, artId: null, icons: {},
  hist: { fps: [], gpu: [], cpu: [], ram: [], down: [], up: [], ping: [] },
};
let ws = null, lastMsg = 0, retryTimer = null;

function connect() {
  clearTimeout(retryTimer);
  if (ws) { ws.onclose = ws.onmessage = ws.onerror = null; try { ws.close(); } catch { /* */ } }
  if (S.conn !== "live") setConn(S.stats ? "reconnecting" : "connecting");
  lastMsg = Date.now();
  const sock = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/`);
  ws = sock;
  sock.onopen = () => {
    // first: who this phone is (the PC may ask its user to allow it)
    sock.send(JSON.stringify({ cmd: "hello", id: DEVICE_ID, name: DEVICE_NAME, app: "web" }));
  };
  sock.onmessage = (e) => {
    if (sock !== ws || typeof e.data !== "string") return;   // binary = Mirror frames, not used here
    lastMsg = Date.now();
    let o; try { o = JSON.parse(e.data); } catch { return; }
    onMessage(o);
  };
  sock.onclose = () => {
    if (sock !== ws) return;
    ws = null;
    if (S.conn === "refused" || S.conn === "off") return;
    setConn(S.stats ? "reconnecting" : "connecting", "Connection closed");
    retryTimer = setTimeout(connect, 2000);
  };
}

function onMessage(o) {
  switch (o.type) {
    case "auth": return onAuth(o);
    case "stats": return onStats(o);
    case "art": S.artId = o.id || null; S.art = o.data ? `data:image/jpeg;base64,${o.data}` : null; renderArt(); return;
    case "icons": Object.assign(S.icons, o.items || {}); return;
    case "action_result": toast(o.message || (o.ok ? "Done" : "Failed"), o.ok); return;
  }
}

function onAuth(o) {
  S.pcName = o.pc || S.pcName;
  if (o.status === "pending") setConn("pending");
  else if (o.status === "allowed") setConn("connecting");
  else if (o.status === "blocked" || o.status === "not_allowed") {
    const pc = o.pc || "The PC";
    S.refused = o.status;
    S.refusedText = o.status === "blocked"
      ? `${pc} blocked this phone. To connect, unblock it on the PC: PerfLink tray icon → Blocked phones.`
      : `${pc} didn't allow this phone. Connect again and click Allow in the window on the PC.`;
    S.stats = null;
    setConn("refused");
    if (ws) { const w = ws; ws = null; try { w.close(); } catch { /* */ } }
  }
}

function push(arr, v) { arr.push(num(v) == null ? NaN : v); if (arr.length > HISTORY) arr.shift(); }
function onStats(s) {
  S.stats = s; S.pcName = s.user || s.host || S.pcName;
  const h = S.hist;
  push(h.fps, s.frames?.fps); push(h.gpu, s.gpu?.util); push(h.cpu, s.cpu?.util); push(h.ram, s.ram?.util);
  push(h.down, s.net?.download_bps); push(h.up, s.net?.upload_bps); push(h.ping, s.net?.ping_ms);
  if (S.conn !== "live") setConn("live");
  if (s.media && !s.media.art_id && S.art) { S.art = null; renderArt(); }
  render();
}

function send(o) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); }
const input = (type, extra = {}) => send({ cmd: "input", type, ...extra });

// Watchdog: the PC sends stats twice a second, so 8 s of silence means the link is dead: reconnect.
setInterval(() => {
  if ((S.conn === "live" || S.conn === "connecting" || S.conn === "reconnecting" || S.conn === "pending")
      && Date.now() - lastMsg > 8000) {
    setConn(S.stats ? "reconnecting" : "connecting", "No data from the PC, retrying");
    connect();
  }
}, 2000);
// iOS pauses pages in the background: on coming back, reconnect straight away if the link is gone
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && S.conn !== "off" && S.conn !== "refused"
      && (!ws || ws.readyState > 1 || Date.now() - lastMsg > 4000)) connect();
});

function disconnect() {
  S.conn = "off";
  clearTimeout(retryTimer);
  if (ws) { const w = ws; ws = null; try { w.close(1000, "bye"); } catch { /* */ } }
  S.stats = null;
  setConn("off");
}

function setConn(c, err) {
  S.conn = c; S.error = err || null;
  const live = $("live");
  live.className = c === "live" ? "ok" : c === "reconnecting" ? "bad" : "";
  live.querySelector("span").textContent = c === "live" ? "Live" : c === "reconnecting" ? "Reconnecting…" : "Connecting…";
  renderCover();
}

// ====================================================================== cover: connecting / waiting / refused / off
function renderCover() {
  const cover = $("cover"), body = $("cover-body"), title = $("cover-title");
  const pc = esc(S.pcName || "the PC");
  const show = !(S.stats && (S.conn === "live" || S.conn === "reconnecting"));
  cover.classList.toggle("hidden", !show);
  if (!show) return;
  if (S.conn === "pending") {
    title.textContent = "Waiting for the PC to allow this phone";
    body.innerHTML = `<div class="spinner"></div>
      <p class="big">On <b>${pc}</b>, click <b>Allow</b> in the PerfLink window.</p>
      <p>This phone shows up as “${esc(DEVICE_NAME)}”. You only need to do this once.</p>`;
  } else if (S.conn === "refused") {
    title.textContent = S.refused === "blocked" ? "This phone is blocked" : "Not allowed";
    body.innerHTML = `<p class="big">${esc(S.refusedText)}</p><button class="btn" id="again">Try again</button>`;
    $("again").onclick = () => { S.refused = null; connect(); };
  } else if (S.conn === "off") {
    title.textContent = "Disconnected";
    body.innerHTML = `<p class="big">Disconnected from ${pc}.</p><button class="btn" id="again">Connect</button>`;
    $("again").onclick = () => connect();
  } else {
    title.textContent = "PerfLink";
    body.innerHTML = `<div class="spinner"></div><p class="big">Connecting to ${pc}…</p>
      ${S.error ? `<p>${esc(S.error)}</p>` : ""}
      <p class="hint">PerfLink must be running on the PC, and this phone on the same Wi-Fi.</p>
      ${!STANDALONE && /iPhone|iPad|iPod|Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 0
        ? `<p class="hint">Tip: tap <b>Share</b> → <b>Add to Home Screen</b> to open PerfLink like an app.</p>` : ""}`;
  }
}

// ====================================================================== pages + top bar
const PAGES = [
  { id: "home", title: "HOME", color: "#4FC3F7" },
  { id: "performance", title: "PERFORMANCE", color: "#FF7A59" },
  { id: "overlay", title: "OVERLAY", color: "#F2C14E" },
  { id: "remote", title: "REMOTE", color: "#7C9CFF" },
  { id: "launcher", title: "LAUNCHER", color: "#F2C14E" },
  { id: "processes", title: "PROCESSES", color: "#FF6FA8" },
  { id: "weather", title: "WEATHER", color: "#6CB8FF" },
];
let page = PAGES.some((p) => p.id === location.hash.slice(1)) ? location.hash.slice(1) : store.get("page", "home");
if (!PAGES.some((p) => p.id === page)) page = "home";

function buildTabs() {
  $("tabs").innerHTML = PAGES.map((p) => `<button class="tab" data-p="${p.id}" style="--c:${p.color}"><i></i>${p.title}</button>`).join("");
  $("tabs").onclick = (e) => { const b = e.target.closest(".tab"); if (b) show(b.dataset.p); };
}
function show(id) {
  page = id; store.set("page", id);
  history.replaceState(null, "", "#" + id);
  document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("on", t.dataset.p === id));
  document.querySelectorAll(".page").forEach((p) => p.classList.toggle("on", p.id === "p-" + id));
  $("btn-home").style.visibility = id === "home" ? "hidden" : "visible";
  $("live").style.display = id === "home" ? "none" : "";
  document.querySelector(".tab.on")?.scrollIntoView({ inline: "nearest", block: "nearest" });
  render();
}
$("btn-home").onclick = () => show("home");
window.addEventListener("hashchange", () => { const id = location.hash.slice(1); if (id !== page && PAGES.some((p) => p.id === id)) show(id); });
$("btn-disconnect").onclick = () => confirmDialog(`Disconnect from ${S.pcName || "the PC"}?`, "Disconnect", disconnect);

// ====================================================================== small canvases (sparklines, charts)
function lineChart(canvas, series, { max = null, grid = true } = {}) {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (!w || !h) return;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
  }
  const g = canvas.getContext("2d");
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);
  if (grid) {
    g.strokeStyle = "rgba(255,255,255,.08)"; g.lineWidth = 1;
    for (let i = 1; i < 4; i++) { const y = Math.round((h * i) / 4) + 0.5; g.beginPath(); g.moveTo(0, y); g.lineTo(w, y); g.stroke(); }
  }
  let top = max;
  if (top == null) { top = 1; for (const s of series) for (const v of s.data) if (isFinite(v) && v > top) top = v; top *= 1.15; }
  for (const s of series) {
    const d = s.data; if (d.length < 2) continue;
    const step = w / (HISTORY - 1), x0 = w - (d.length - 1) * step;
    g.beginPath(); let on = false;
    d.forEach((v, i) => {
      if (!isFinite(v)) { on = false; return; }
      const x = x0 + i * step, y = h - 2 - (Math.min(v, top) / top) * (h - 4);
      on ? g.lineTo(x, y) : g.moveTo(x, y); on = true;
    });
    g.strokeStyle = s.color; g.lineWidth = 1.6; g.lineJoin = "round"; g.stroke();
    if (s.fill) { g.lineTo(w, h); g.lineTo(x0, h); g.closePath(); g.fillStyle = s.fill; g.fill(); }
  }
}
function frameBars(canvas, ms) {
  const dpr = window.devicePixelRatio || 1, w = canvas.clientWidth, h = canvas.clientHeight;
  if (!w || !h) return;
  canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
  const g = canvas.getContext("2d"); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, w, h);
  if (!ms || !ms.length) return;
  const avg = ms.reduce((a, b) => a + b, 0) / ms.length, top = Math.max(...ms, avg * 2);
  const bw = w / ms.length;
  ms.forEach((v, i) => {
    g.fillStyle = v > avg * 1.6 ? C.bad : v > avg * 1.25 ? C.warn : C.accent;
    const bh = Math.max(1, (v / top) * h);
    g.fillRect(i * bw, h - bh, Math.max(1, bw - 1), bh);
  });
}

// ====================================================================== HOME
let use24 = store.get("clock_24h", "0") === "1";
document.querySelectorAll(".fmt button").forEach((b) => {
  b.onclick = () => { use24 = b.dataset.f === "24"; store.set("clock_24h", use24 ? "1" : "0"); tickClock(); };
});
function tickClock() {
  const now = new Date(), p = (n) => String(n).padStart(2, "0");
  const h12 = now.getHours() % 12 || 12;
  $("ch").textContent = use24 ? p(now.getHours()) : String(h12);
  $("cm").textContent = p(now.getMinutes());
  $("cs").textContent = p(now.getSeconds());
  $("campm").textContent = now.getHours() < 12 ? "AM" : "PM";
  $("campm").style.display = use24 ? "none" : "";
  $("date").textContent = now.toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long", year: "numeric" });
  document.querySelectorAll(".fmt button").forEach((b) => b.classList.toggle("on", (b.dataset.f === "24") === use24));
}
setInterval(tickClock, 1000); tickClock();

// clock scales to the space left: below it the date + weather (~88px); across, the switch + seconds (~84px)
function sizeClock() {
  const a = $("clock-area"); if (!a.clientWidth) return;
  const sz = Math.max(28, Math.min(150, Math.min((a.clientHeight - 88) / 1.18, (a.clientWidth - 84) / 2.6)));
  $("clock").style.fontSize = sz + "px";
  $("cs").style.fontSize = Math.max(sz * 0.24, 12) + "px";
  $("campm").style.fontSize = Math.max(sz * 0.2, 11) + "px";
}
new ResizeObserver(sizeClock).observe($("clock-area"));

const ICON = {
  play: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M7 3.5l13.5 8.5L7 20.5z"/></svg>',
  pause: '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="5.5" y="3.5" width="4.5" height="17" rx="1"/><rect x="14" y="3.5" width="4.5" height="17" rx="1"/></svg>',
  speaker: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5L6.5 9H3v6h3.5L11 19z" fill="currentColor"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/></svg>',
  muted: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5L6.5 9H3v6h3.5L11 19z" fill="currentColor"/><path d="M16 9.5l5 5 M21 9.5l-5 5" stroke="#FF6B6B"/></svg>',
  msg: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H11l-6 4v-4H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"/></svg>',
  call: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M6.6 10.8a15 15 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.6 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.6 3.6a1 1 0 0 1-.25 1z"/></svg>',
  wa: '<svg viewBox="0 0 32 32" class="wa-logo"><circle cx="16" cy="15" r="13" fill="#25D366"/><path d="M5 29l2.5-6 4 3z" fill="#25D366"/><path d="M11.5 9.5c.6-.6 1.6-.5 2 .2l1.2 2.2c.3.6.2 1.3-.3 1.7l-.8.7c.8 1.7 2.2 3.1 3.9 3.9l.7-.8c.4-.5 1.1-.6 1.7-.3l2.2 1.2c.7.4.8 1.4.2 2l-1 1c-1 1-2.6 1.2-3.9.6a14 14 0 0 1-6.9-6.9c-.6-1.3-.4-2.9.6-3.9z" fill="#0B1A12"/></svg>',
};

function bindSlider(el, onMove, onDone) {
  const paint = () => el.style.setProperty("--f", ((el.value - el.min) / (el.max - el.min)) * 100 + "%");
  el.addEventListener("input", () => { el.dataset.drag = "1"; paint(); onMove && onMove(+el.value); });
  el.addEventListener("change", () => { paint(); onDone && onDone(+el.value); delete el.dataset.drag; });
  el.paint = paint;
}
let lastVolSent = 0;
bindSlider($("vol"), (v) => { const t = Date.now(); if (t - lastVolSent > 80) { send({ cmd: "volume", value: v }); lastVolSent = t; } },
  (v) => send({ cmd: "volume", value: v }));
bindSlider($("seek"), null, (v) => { const d = S.stats?.media?.duration_s; if (d) send({ cmd: "media", action: "seek", value: (d * v) / 1000 }); });
document.querySelectorAll("[data-media]").forEach((b) => { b.onclick = () => send({ cmd: "media", action: b.dataset.media }); });
$("play").onclick = () => send({ cmd: "media", action: "play_pause" });
$("mute").onclick = () => send({ cmd: "mute" });
$("host-btn").onclick = () => settingsDialog();
$("wx-row").onclick = () => show("weather");
$("wa").onclick = () => { const wa = S.stats?.whatsapp; if (wa && wa.notifications?.length) waDialog(wa); };

function renderArt() {
  const hasTrack = !!S.stats?.media?.title;
  $("art").innerHTML = S.art && hasTrack ? `<img src="${S.art}" alt="">` : "♪";
}

function tile(el, label, color, value, unit, sub, data, max) {
  if (!el.firstChild) el.innerHTML = '<canvas></canvas><div class="lbl"></div><div class="val"></div><div class="sub"></div>';
  el.querySelector(".lbl").textContent = label; el.querySelector(".lbl").style.color = color;
  el.querySelector(".val").innerHTML = `${esc(value)}<small>${esc(unit)}</small>`;
  el.querySelector(".sub").textContent = sub;
  const rgba = color + "8C";
  lineChart(el.querySelector("canvas"), [{ data, color: rgba }], { max, grid: false });
}

function renderHome(s) {
  const m = s.media || {}, v = s.volume || {};
  $("host-dot").style.background = S.conn === "live" ? C.good : C.warn;
  $("host-name").textContent = `${s.kind === "Laptop" ? "💻" : "🖥️"} ${s.user || s.host || ""}`;
  $("host-ip").textContent = s.net?.local_ip || "";
  $("link-chip").textContent = /^(127\.|localhost)/.test(location.hostname) ? "This PC" : "Wi-Fi";
  // WhatsApp (from WhatsApp Desktop on the PC)
  const wa = s.whatsapp;
  $("wa").classList.toggle("hidden", !wa);
  if (wa) {
    const count = (n, one, many, ic) => `<span class="wa-count ${n > 0 ? "on" : ""}"><span class="ic">${ic}</span><b>${n}</b>${n === 1 ? one : many}</span>`;
    $("wa").innerHTML = ICON.wa + (!wa.installed || (!wa.running && !(wa.notifications || []).length)
      ? `<span>${!wa.installed ? "WhatsApp Desktop isn't set up on the PC" : "WhatsApp isn't open on the PC"}</span>`
      : count(wa.messages || 0, "message", "messages", ICON.msg) + count(wa.calls || 0, "call", "calls", ICON.call));
  }
  // weather
  const w = s.weather;
  $("wx-row").classList.toggle("hidden", !(w && num(w.temp_c) != null));
  if (w && num(w.temp_c) != null) {
    const bits = [`↑${f0(w.high_c)}° ↓${f0(w.low_c)}°`, `Feels ${f0(w.feels_c)}°`];
    if (num(w.humidity) != null) bits.push(`💧${f0(w.humidity)}%`);
    if (num(w.rain_pct) != null) bits.push(`☔${f0(w.rain_pct)}%`);
    $("wx-row").innerHTML = `<span class="emo">${weatherEmoji(w.code, w.is_day !== false)}</span><span class="t">${f0(w.temp_c)}°</span>
      <span class="lines"><div class="l1">${esc(w.condition || "")} · ${w.location_source === "manual" ? "" : "📍"}${esc(w.city || "")}</div>
      <div class="l2">${esc(bits.join(" · "))}</div></span>`;
  }
  // media
  const hasTrack = m.title != null;
  $("m-app").textContent = m.app ? `MEDIA · ${m.app.toUpperCase()}` : "MEDIA";
  $("m-title").textContent = m.title || "Nothing playing";
  $("m-artist").textContent = !hasTrack ? "Play something on the PC" : m.artist ? m.artist : m.playing === false ? "Paused" : "";
  $("play").innerHTML = m.playing ? ICON.pause : ICON.play;
  $("mute").innerHTML = v.muted ? ICON.muted : ICON.speaker;
  $("m-pos").textContent = mmss(m.position_s); $("m-dur").textContent = mmss(m.duration_s);
  const seek = $("seek");
  seek.disabled = !m.duration_s;
  if (!seek.dataset.drag) { seek.value = m.duration_s ? Math.round(((m.position_s || 0) / m.duration_s) * 1000) : 0; seek.paint(); }
  const vol = $("vol");
  vol.disabled = num(v.level) == null;
  if (!vol.dataset.drag) { vol.value = v.level || 0; vol.paint(); }
  $("vol-n").textContent = num(v.level) == null ? "–" : f0(v.level);
  renderArt();
  // stat tiles
  const h = S.hist, ram = s.ram || {}, net = s.net || {};
  tile($("t-cpu"), "CPU", C.hCpu, f0(s.cpu?.util), "%", shortCpu(s.cpu?.name), h.cpu, 100);
  tile($("t-gpu"), "GPU", C.hGpu, f0(s.gpu?.util), "%", `${temp(s.gpu?.temp_c)} · ${num(s.gpu?.power_w) != null ? f0(s.gpu.power_w) + " W" : "N/A"}`, h.gpu, 100);
  const used = num(ram.used_mb) != null ? (ram.used_mb / 1024).toFixed(1) : "N/A";
  const split = (ram.modules || []).length > 1 ? " · " + ram.modules.map((x) => x.gb).join("+") : "";
  tile($("t-ram"), `RAM (${f0(ram.util)}%)`, C.hRam, used, "GB", `${used} / ${num(ram.total_mb) != null ? f0(ram.total_mb / 1024) : "?"} GB${split}`, h.ram, 100);
  const [dv, du] = byteRate(net.download_bps), [uv, uu] = byteRate(net.upload_bps);
  tile($("t-net"), "NETWORK", C.hNet, dv, du, `↑ ${uv} ${uu} · ${num(net.ping_ms) != null ? f0(net.ping_ms) + " ms" : "– ms"}`, h.down, null);
}

// ====================================================================== PERFORMANCE + OVERLAY
function statusLine(el) {
  const s = S.stats;
  const [dot, label] = S.conn === "live" ? [C.good, "Live"] : S.conn === "reconnecting" ? [C.bad, "Reconnecting…"] : [C.warn, "Connecting…"];
  el.innerHTML = `<i style="width:8px;height:8px;border-radius:50%;background:${dot};flex:none"></i><b>${esc(s?.user || s?.host || "")}</b><span>· ${label}</span>`;
}
const kv = (k, v) => `<div class="kv"><span>${esc(k)}</span><span>${v == null ? "N/A" : esc(v)}</span></div>`;
const mini = (k, v, color = C.text) => `<div class="mini"><div class="k">${esc(k)}</div><div class="v mono" style="color:${color}">${esc(v)}</div></div>`;

function renderPerformance(s) {
  statusLine($("perf-status"));
  const root = $("perf");
  if (!root.firstChild) {
    root.innerHTML = `<div class="card" id="c-perf"></div><div class="card" id="c-gpu"></div><div class="card" id="c-cpu"></div><div class="card" id="c-net"></div><div class="card hidden" id="c-err"></div>`;
  }
  const f = s.frames || {}, g = s.gpu || {}, c = s.cpu || {}, r = s.ram || {}, n = s.net || {}, h = S.hist;
  const keepCanvas = (el, html) => { el.innerHTML = html; return el; };
  keepCanvas($("c-perf"), `<h3>Performance</h3><div class="sub">${esc(f.app || "No game detected")}</div>
    <div class="bigfps"><b class="mono" style="color:${fpsColor(f.fps)}">${f0(f.fps)}</b><span class="dim">FPS</span></div>
    <div class="minis">${mini("Frame time", num(f.frame_time_ms) != null ? f1(f.frame_time_ms) + " ms" : "N/A")}${mini("1% low", f0(f.low1_fps), fpsColor(f.low1_fps))}${mini("0.1% low", f0(f.low01_fps), fpsColor(f.low01_fps))}</div>
    <div class="cap">FPS (last ${HISTORY / 2}s)</div><canvas class="chart" id="ch-fps"></canvas>
    <div class="cap">Frame times (last second) — spikes = stutter</div><canvas class="chart" id="ch-ft" style="height:50px"></canvas>
    ${kv("Frame gen lag", null)}${kv("Upscale upgrade", null)}`);
  lineChart($("ch-fps"), [{ data: h.fps, color: C.accent }]);
  frameBars($("ch-ft"), f.frame_times);

  const vramPct = num(g.vram_used_mb) != null && g.vram_total_mb ? (g.vram_used_mb / g.vram_total_mb) * 100 : 0;
  keepCanvas($("c-gpu"), `<h3>GPU</h3><div class="sub">${esc(g.name || "")}</div>
    <div class="minis">${mini("Util", pct(g.util), C.gpu)}${mini("Power", num(g.power_w) != null ? f0(g.power_w) + " W" : "N/A")}${mini("Temp", temp(g.temp_c), tempColor(g.temp_c))}${mini("Hotspot", temp(g.hotspot_c), tempColor(num(g.hotspot_c) != null ? g.hotspot_c - 10 : null))}</div>
    <canvas class="chart" id="ch-gpu"></canvas>
    ${kv("Core clock", num(g.core_clock_mhz) != null ? f0(g.core_clock_mhz) + " MHz" : null)}
    ${kv("GPU mem clock", num(g.mem_clock_mhz) != null ? f0(g.mem_clock_mhz) + " MHz" : null)}
    ${kv("GPU mem util", num(g.vram_used_mb) != null ? f0(g.vram_used_mb) + " MB" : null)}
    ${kv("Fan", num(g.fan_rpm) == null ? null : g.fan_rpm === 0 ? "0 RPM (zero-RPM mode)" : f0(g.fan_rpm) + " RPM")}
    ${kv("Voltage", num(g.voltage_v) > 0 ? g.voltage_v.toFixed(3) + " V" : null)}
    ${kv("VRAM", num(g.vram_used_mb) != null && num(g.vram_total_mb) != null ? `${gbOf(g.vram_used_mb)} / ${gbOf(g.vram_total_mb)}` : null)}
    <div class="bar" style="--c:${C.gpu}"><i style="width:${vramPct}%"></i></div>`);
  lineChart($("ch-gpu"), [{ data: h.gpu, color: C.gpu }], { max: 100 });

  const mods = r.modules || [];
  const ramPct = num(r.util) || 0;
  keepCanvas($("c-cpu"), `<h3>CPU &amp; Memory</h3><div class="sub">${esc(c.name || "")}</div>
    <div class="minis">${mini("Util", pct(c.util), C.cpu)}${mini("Clock", num(c.clock_mhz) != null ? f0(c.clock_mhz) + " MHz" : "N/A")}${mini("Temp", temp(c.temp_c), tempColor(c.temp_c))}${mini("Power", num(c.power_w) != null ? f0(c.power_w) + " W" : "N/A")}</div>
    <canvas class="chart" id="ch-cpu"></canvas>
    ${(c.per_core || []).length ? `<div class="cap">Per thread</div><div class="cores">${c.per_core.map((v) => `<span><i style="height:${Math.max(0, Math.min(100, v))}%"></i></span>`).join("")}</div>` : ""}
    <div class="cap">Memory</div>
    ${kv("RAM", num(r.used_mb) != null ? `${gbOf(r.used_mb)} / ${gbOf(r.total_mb || 0)} (${f0(r.util)}%)` : null)}
    <div class="bar" style="--c:${C.hRam}"><i style="width:${ramPct}%"></i></div>
    ${mods.length ? `<table class="ram-table">${mods.map((m, i) => `<tr><td>RAM ${i + 1}</td><td>${m.gb} GB</td><td>${esc(m.type || "")}</td><td>${m.mts ? m.mts + " MT/s" : ""}</td></tr>`).join("")}</table>` : ""}`);
  lineChart($("ch-cpu"), [{ data: h.cpu, color: C.cpu }], { max: 100 });

  keepCanvas($("c-net"), `<h3>Network</h3><div class="sub">${esc([n.interface, n.local_ip, n.link_mbps ? n.link_mbps + " Mbps link" : null].filter(Boolean).join(" · "))}</div>
    <div class="minis">${mini("Down", bps(n.download_bps), C.down)}${mini("Up", bps(n.upload_bps), C.up)}${mini("Ping", num(n.ping_ms) != null ? f0(n.ping_ms) + " ms" : "N/A", pingColor(n.ping_ms))}</div>
    <canvas class="chart" id="ch-net"></canvas>
    ${kv("Ping average", num(n.ping_avg_ms) != null ? f1(n.ping_avg_ms) + " ms" : null)}
    ${kv("Jitter", num(n.jitter_ms) != null ? f1(n.jitter_ms) + " ms" : null)}
    ${kv("Packet loss", num(n.packet_loss_pct) != null ? f1(n.packet_loss_pct) + " %" : null)}
    ${kv("Ping host", n.ping_host)}
    ${kv("Received", num(n.total_recv_mb) != null ? (n.total_recv_mb >= 1000 ? (n.total_recv_mb / 1000).toFixed(2) + " GB" : f1(n.total_recv_mb) + " MB") : null)}
    ${kv("Sent", num(n.total_sent_mb) != null ? (n.total_sent_mb >= 1000 ? (n.total_sent_mb / 1000).toFixed(2) + " GB" : f1(n.total_sent_mb) + " MB") : null)}
    ${kv("Connections", n.connections != null ? String(n.connections) : null)}`);
  lineChart($("ch-net"), [{ data: h.down, color: C.down }, { data: h.up, color: C.up }]);

  const errs = s.errors || [];
  $("c-err").classList.toggle("hidden", !errs.length);
  $("c-err").innerHTML = errs.map((e) => `<div style="color:${C.warn};font-size:12px">${esc(e)}</div>`).join("");
}

function renderOverlay(s) {
  statusLine($("ov-status"));
  const f = s.frames || {}, g = s.gpu || {}, c = s.cpu || {}, r = s.ram || {}, n = s.net || {};
  const split = !(r.modules || []).length ? "N/A" : r.modules.length === 1 ? `${r.modules[0].gb} GB` : r.modules.map((m) => `${m.gb} GB`).join(" + ");
  const rows = [
    ["FPS", f0(f.fps), fpsColor(f.fps)],
    ["FRAME TIME", num(f.frame_time_ms) != null ? f1(f.frame_time_ms) + " ms" : "N/A", C.text],
    ["99th% FPS", f0(f.low1_fps), fpsColor(f.low1_fps)],
    ["GPU UTIL", pct(g.util), C.text],
    ["GPU PWR", num(g.power_w) != null ? f0(g.power_w) + " W" : "N/A", C.text],
    ["GPU TEMP", temp(g.temp_c), tempColor(g.temp_c)],
    ["GPU MEM UTIL", num(g.vram_used_mb) != null ? f0(g.vram_used_mb) + " MB" : "N/A", C.text],
    ["CPU UTIL", pct(c.util), C.text],
    ["SYSTEM MEM UTIL", num(r.used_mb) != null ? (r.used_mb / 1024).toFixed(1) + " GB" : "N/A", C.text],
    ["RAM", split, C.text],
    ["GPU MEM CLK", num(g.mem_clock_mhz) != null ? f0(g.mem_clock_mhz) + " MHz" : "N/A", C.text],
    ["CPU TEMP", temp(c.temp_c), tempColor(c.temp_c)],
    ["NET ↓", bps(n.download_bps), C.down],
    ["NET ↑", bps(n.upload_bps), C.up],
    ["PING", num(n.ping_ms) != null ? f0(n.ping_ms) + " ms" : "N/A", pingColor(n.ping_ms)],
    ["FRAME GEN LAG", "N/A", C.dim],
    ["UPSCALE UPGRADE", "N/A", C.dim],
  ];
  $("ov").innerHTML = rows.map(([k, v, col]) => `<div class="ov-row"><span>${k}</span><b class="mono" style="color:${col}">${esc(v)}</b></div>`).join("")
    + (f.app ? `<div class="sub" style="padding:8px 4px">Tracking: ${esc(f.app)}</div>` : "");
}

// ====================================================================== REMOTE: touchpad + keyboard
(function touchpad() {
  const pad = $("pad");
  const pts = new Map();          // pointerId -> {x, y}
  let g = null;                   // the current gesture
  pad.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    try { pad.setPointerCapture(e.pointerId); } catch { /* not a live pointer: fine */ }
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (!g) g = { start: performance.now(), maxFingers: 0, travel: 0, rx: 0, ry: 0, scroll: 0 };
    g.maxFingers = Math.max(g.maxFingers, pts.size);
    pad.classList.add("touch");
  });
  pad.addEventListener("pointermove", (e) => {
    const p = pts.get(e.pointerId); if (!p || !g) return;
    e.preventDefault();
    const dx = e.clientX - p.x, dy = e.clientY - p.y;
    p.x = e.clientX; p.y = e.clientY;
    if (pts.size === 1 && g.maxFingers === 1) {
      const d = Math.hypot(dx, dy);
      g.travel += d;
      const gain = 1.2 + Math.min(d * 0.06, 2.4);   // gentle acceleration, like the Android app
      g.rx += dx * gain; g.ry += dy * gain;
      const sx = Math.trunc(g.rx), sy = Math.trunc(g.ry);
      if (sx || sy) { input("move", { dx: sx, dy: sy }); g.rx -= sx; g.ry -= sy; }
    } else if (pts.size >= 2) {
      const ady = dy / pts.size;                      // each finger reports its own move: average them
      g.travel += Math.abs(ady);
      g.scroll += ady * 4;                            // natural scrolling: content follows the fingers
      if (Math.abs(g.scroll) >= 40) { input("scroll", { dy: Math.trunc(g.scroll) }); g.scroll = 0; }
    }
  });
  const up = (e) => {
    if (!pts.delete(e.pointerId) || !g) return;
    if (pts.size === 0) {
      if (performance.now() - g.start < 260 && g.travel < 10) input("click", { button: g.maxFingers >= 2 ? "right" : "left" });
      g = null;
      pad.classList.remove("touch");
    }
  };
  pad.addEventListener("pointerup", up);
  pad.addEventListener("pointercancel", up);
  // keep iOS from scrolling / zooming the page while using the pad
  pad.addEventListener("touchmove", (e) => e.preventDefault(), { passive: false });
  pad.addEventListener("gesturestart", (e) => e.preventDefault());

  let hold = false;
  const press = (el, fn) => el.addEventListener("pointerdown", (e) => { e.preventDefault(); fn(); });
  press($("b-left"), () => input("click", { button: "left" }));
  press($("b-right"), () => input("click", { button: "right" }));
  press($("b-hold"), () => {
    hold = !hold;
    $("b-hold").textContent = hold ? "Release" : "Hold";
    $("b-hold").classList.toggle("on", hold);
    input("button", { button: "left", down: hold });
  });
})();

(function keyboard() {
  const K = (label, send = label, w = 1, kind = "char") => ({ label, send, w, kind });
  const SPECIAL = [K("Esc", "esc", 1, "key"), K("Tab", "tab", 1, "key"), K("Ctrl", "ctrl", 1, "mod"), K("Alt", "alt", 1, "mod"),
    K("Win", "win", 1, "mod"), K("←", "left", 1, "key"), K("↑", "up", 1, "key"), K("↓", "down", 1, "key"), K("→", "right", 1, "key"),
    K("Del", "delete", 1, "key")];
  const chars = (s) => [...s].map((c) => K(c));
  const LETTERS = [chars("qwertyuiop"), chars("asdfghjkl"),
    [K("⇧", "shift", 1.4, "shift"), ...chars("zxcvbnm"), K("⌫", "backspace", 1.4, "key")],
    [K("?123", "", 1.5, "layer"), K(","), K("space", " ", 4), K("."), K("↵", "enter", 1.5, "key")]];
  const SYMBOLS = [chars("1234567890"), chars("@#$%&*-+()"), [...chars("!?\"':;/\\_="), K("⌫", "backspace", 1.4, "key")],
    [K("ABC", "", 1.5, "layer"), K("<"), K(">"), K("space", " ", 3), K("["), K("]"), K("↵", "enter", 1.5, "key")]];
  const REPEAT = new Set(["backspace", "delete", "left", "right", "up", "down"]);
  let symbols = false, shift = false, mods = new Set();
  const kbd = $("kbd");

  function draw() {
    const rows = [SPECIAL, ...(symbols ? SYMBOLS : LETTERS)];
    kbd.innerHTML = rows.map((row, i) => {
      const keys = row.map((k, j) => {
        const on = (k.kind === "mod" && mods.has(k.send)) || (k.kind === "shift" && shift);
        const label = k.kind === "char" && shift && !symbols ? k.label.toUpperCase() : k.label;
        const fn = i === 0 || k.kind !== "char";
        return `<button class="key ${fn ? "fn" : ""} ${on ? "on" : ""}" style="--w:${k.w}" data-r="${i}" data-k="${j}">${esc(label)}</button>`;
      }).join("");
      const stagger = i === 2 && !symbols ? '<span class="kgap"></span>' : "";
      return `<div class="krow ${i === 0 ? "special" : ""}">${stagger}${keys}${stagger}</div>`;
    }).join("");
  }
  function keyOf(el) { const rows = [SPECIAL, ...(symbols ? SYMBOLS : LETTERS)]; return rows[+el.dataset.r][+el.dataset.k]; }
  function press(k) {
    if (k.kind === "layer") { symbols = !symbols; draw(); return; }
    if (k.kind === "shift") { shift = !shift; draw(); return; }
    if (k.kind === "mod") { mods.has(k.send) ? mods.delete(k.send) : mods.add(k.send); draw(); return; }
    const all = [...mods, ...(shift ? ["shift"] : [])];
    if (k.kind === "key") input("key", { key: k.send, mods: all });
    else if (!mods.size) input("text", { text: shift ? k.send.toUpperCase() : k.send });
    else input("key", { key: k.send, mods: all });             // a shortcut: Ctrl+C, Alt+F4, Win+D…
    if (mods.size || shift) { mods.clear(); shift = false; draw(); }
  }
  let timer = null;
  const stop = () => { clearTimeout(timer); clearInterval(timer); timer = null; kbd.querySelectorAll(".held").forEach((x) => x.classList.remove("held")); };
  kbd.addEventListener("pointerdown", (e) => {
    const el = e.target.closest(".key"); if (!el) return;
    e.preventDefault();
    stop();
    const k = keyOf(el);
    el.classList.add("held");
    press(k);
    if (REPEAT.has(k.send)) timer = setTimeout(() => { timer = setInterval(() => press(k), 55); }, 420);
  });
  ["pointerup", "pointercancel", "pointerleave"].forEach((t) => kbd.addEventListener(t, stop));
  kbd.addEventListener("touchmove", (e) => e.preventDefault(), { passive: false });
  draw();
})();

// ====================================================================== LAUNCHER
const LI = {
  terminal: "M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z M7 10h.01 M10.3 10h.01 M13.7 10h.01 M17 10h.01 M7 14h.01 M17 14h.01 M10 14h4",
  files: "M3 7.5A2 2 0 0 1 5 5.5h4l2 2.2h8a2 2 0 0 1 2 2v7.8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
  vscode: "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z M14 3v5h5 M10 12.5l-2 2 2 2 M14 12.5l2 2-2 2",
  browser: "M12 3a9 9 0 1 0 0.01 0z M3 12h18 M12 3c2.4 2.5 3.7 5.5 3.7 9s-1.3 6.5-3.7 9 M12 3c-2.4 2.5-3.7 5.5-3.7 9s1.3 6.5 3.7 9",
  spotify: "M4 15v-3a8 8 0 0 1 16 0v3 M4 15h3v5H5a1 1 0 0 1-1-1z M20 15h-3v5h2a1 1 0 0 0 1-1z",
  calculator: "M6.5 3h11a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5v-15A1.5 1.5 0 0 1 6.5 3z M8 6.5h8v3.5H8z M8.5 14h.01 M12 14h.01 M15.5 14h.01 M8.5 17.5h.01 M12 17.5h.01 M15.5 17.5h.01",
  settings: "M12 9a3 3 0 1 0 0.01 0z M12 5.2a6.8 6.8 0 1 0 0.01 0z M12 2.5v2.7 M12 18.8v2.7 M2.5 12h2.7 M18.8 12h2.7 M5.3 5.3l1.9 1.9 M16.8 16.8l1.9 1.9 M5.3 18.7l1.9-1.9 M16.8 7.2l1.9-1.9",
  snip: "M6 3a3 3 0 1 0 0.01 0z M6 15a3 3 0 1 0 0.01 0z M8.2 8.2L20 20 M8.2 15.8L20 4 M14.5 12.2l.01 0",
  taskmgr: "M4 4v16h16 M8 16v-3 M12 16V8 M16 16v-5",
  mute: "M11 5L6.5 9H3v6h3.5L11 19z M16 9.5l5 5 M21 9.5l-5 5",
  unmute: "M11 5L6.5 9H3v6h3.5L11 19z M15.5 8.5a5 5 0 0 1 0 7 M18.5 5.5a9 9 0 0 1 0 13",
  screen_off: "M20 14.5A8.5 8.5 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z",
  lock: "M6 11h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1z M8 11V8a4 4 0 0 1 8 0v3",
};
const TILES = [
  ["terminal", "Terminal", "#6B7280"], ["files", "Files", "#D99A2B"], ["vscode", "VS Code", "#3B82F6"], ["browser", "Browser", "#06B6D4"],
  ["spotify", "Spotify", "#22C55E"], ["calculator", "Calculator", "#64748B"], ["settings", "Settings", "#94A3B8"], ["snip", "Snip", "#14B8A6"],
  ["taskmgr", "Task Manager", "#16A34A"], ["mute", "Mute", "#F59E0B"], ["screen_off", "Screen Off", "#6366F1"], ["lock", "Lock", "#EF4444"],
];
const svgPath = (d) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="${d}"/></svg>`;
$("launch").addEventListener("click", (e) => {
  const t = e.target.closest(".ltile"); if (!t) return;
  if (!ws || S.conn !== "live") { toast("Not connected to the PC", false); return; }
  send({ cmd: "action", id: t.dataset.id });
});
function renderLauncher(s) {
  const hints = s.launcher || {}, muted = s.volume?.muted === true;
  $("launch").innerHTML = TILES.map(([id, label, color]) => {
    const m = id === "mute" && muted;
    const hint = hints[id] ?? (id === "vscode" ? null : "");
    return `<button class="ltile ${m ? "on" : ""}" data-id="${id}" style="--c:${color}"><span class="ic">${svgPath(m ? LI.unmute : LI[id])}</span>
      <b>${m ? "Unmute" : label}</b>${hint ? `<small>${esc(hint)}</small>` : ""}</button>`;
  }).join("");
}

// ====================================================================== PROCESSES
let procSort = "cpu";
document.querySelectorAll(".phead [data-sort]").forEach((b) => {
  b.onclick = () => { procSort = b.dataset.sort; document.querySelectorAll(".phead [data-sort]").forEach((x) => x.classList.toggle("on", x === b)); render(); };
});
function renderProcesses(s) {
  const p = s.processes || { apps: [] };
  $("proc-status").innerHTML = `<b>${p.total_apps ?? p.apps.length} apps</b><span>· ${p.total_processes ?? "?"} processes</span>`;
  const apps = [...p.apps].sort((a, b) => (procSort === "cpu" ? (b.cpu - a.cpu) || (b.ram_mb - a.ram_mb) : b.ram_mb - a.ram_mb)).slice(0, 80);
  $("plist").innerHTML = apps.map((a) => {
    const icon = a.icon_id && S.icons[a.icon_id] ? `<img src="data:image/png;base64,${S.icons[a.icon_id]}" alt="">` : '<span class="noicon"></span>';
    return `<div class="prow">${icon}<div class="nm"><b>${esc(a.name)}</b><span class="sub">${esc(a.exe)}${a.count > 1 ? ` · ${a.count} processes` : ""}</span></div>
      <span class="num mono" style="color:${a.cpu >= 50 ? C.bad : a.cpu >= 15 ? C.warn : C.text}">${(a.cpu || 0).toFixed(1)} %</span>
      <span class="num mono">${gbOf(a.ram_mb || 0)}</span></div>`;
  }).join("");
}

// ====================================================================== WEATHER
function renderWeather(s) {
  const d = s.weather_detail;
  const st = $("wx-status");
  if (!d || num(d.temp_c) == null) {
    st.innerHTML = "";
    $("wx").innerHTML = `<p class="dim" style="margin:auto">Finding the PC's location and weather…</p>`;
    return;
  }
  st.innerHTML = `<b>📍 ${esc([d.place || d.city, d.country].filter(Boolean).join(", "))}</b>`
    + (d.updated ? `<span>· updated ${ago(Date.now() / 1000 - d.updated)} ago</span>` : "");
  const fact = (k, v) => (v == null ? "" : `<div><span>${esc(k)}</span><br>${esc(v)}</div>`);
  const hours = (d.hourly || []).map((h) => `<div class="hour"><div class="dim">${esc(h.time)}</div><div class="e">${weatherEmoji(h.code, h.is_day !== false)}</div>
    <div>${f0(h.temp_c)}°</div>${h.rain_pct ? `<div style="color:#6CB8FF;font-size:10px">${f0(h.rain_pct)}%</div>` : ""}</div>`).join("");
  const days = d.daily || [];
  const lo = Math.min(...days.map((x) => x.low_c ?? 99)), hi = Math.max(...days.map((x) => x.high_c ?? -99));
  const span = Math.max(1, hi - lo);
  const dayRows = days.map((x, i) => {
    const name = i === 0 ? "Today" : new Date(x.date + "T12:00").toLocaleDateString(undefined, { weekday: "short" });
    const l = ((x.low_c - lo) / span) * 100, w = ((x.high_c - x.low_c) / span) * 100;
    return `<div class="day"><span>${esc(name)}</span><span>${weatherEmoji(x.code, true)}</span>
      <span style="display:flex;align-items:center;gap:8px"><span class="dim" style="width:30px;text-align:right">${f0(x.low_c)}°</span>
      <span class="rng" style="flex:1"><i style="left:${l}%;width:${w}%"></i></span><span style="width:30px">${f0(x.high_c)}°</span></span>
      <span class="dim" style="text-align:right">${x.rain_pct ? "☔" + f0(x.rain_pct) + "%" : ""}</span></div>`;
  }).join("");
  $("wx").innerHTML = `<div class="wx-now">
      <div class="wx-big"><span class="emo">${weatherEmoji(d.code, d.is_day !== false)}</span><b>${f0(d.temp_c)}°</b>
        <div><div style="font-size:16px;font-weight:600">${esc(d.condition || "")}</div><div class="dim">↑${f0(d.high_c)}° ↓${f0(d.low_c)}° · Feels ${f0(d.feels_c)}°</div></div></div>
      <div class="wx-facts">
        ${fact("Humidity", num(d.humidity) != null ? f0(d.humidity) + "%" : null)}${fact("Rain", num(d.rain_pct) != null ? f0(d.rain_pct) + "%" : null)}
        ${fact("Wind", num(d.wind_kmh) != null ? `${f0(d.wind_kmh)} km/h ${d.wind_dir || ""}` : null)}${fact("Gusts", num(d.gust_kmh) != null ? f0(d.gust_kmh) + " km/h" : null)}
        ${fact("Air quality", num(d.aqi) != null ? `${f0(d.aqi)} · ${d.aqi_label || ""}` : null)}${fact("UV index", num(d.uv) != null ? f1(d.uv) : null)}
        ${fact("Pressure", num(d.pressure_hpa) != null ? f0(d.pressure_hpa) + " hPa" : null)}${fact("Visibility", num(d.visibility_km) != null ? f1(d.visibility_km) + " km" : null)}
        ${fact("Dew point", num(d.dew_point_c) != null ? f0(d.dew_point_c) + "°" : null)}${fact("Clouds", num(d.cloud_pct) != null ? f0(d.cloud_pct) + "%" : null)}
        ${fact("Sunrise", d.sunrise)}${fact("Sunset", d.sunset)}
      </div></div>
    <div class="wx-fc"><div class="cap" style="margin:0">Next 24 hours</div><div class="hours">${hours}</div>
      <div class="cap" style="margin:0">7 days</div><div class="days">${dayRows}</div></div>`;
}

// ====================================================================== dialogs + toast
function openDialog(html, onBuild) {
  const d = $("dialog");
  d.innerHTML = `<div class="dlg">${html}</div>`;
  d.classList.remove("hidden");
  d.onclick = (e) => { if (e.target === d) closeDialog(); };
  onBuild && onBuild(d);
}
function closeDialog() { $("dialog").classList.add("hidden"); $("dialog").innerHTML = ""; }
function confirmDialog(text, okLabel, onOk) {
  openDialog(`<h2>${esc(text)}</h2><div class="row"><button class="btn ghost" id="d-no">Cancel</button><button class="btn danger" id="d-ok">${esc(okLabel)}</button></div>`, () => {
    $("d-no").onclick = closeDialog;
    $("d-ok").onclick = () => { closeDialog(); onOk(); };
  });
}
function settingsDialog() {
  const w = S.stats?.weather || {}, manual = w.location_source === "manual";
  openDialog(`<h2>Settings</h2>
    <p>${manual ? `Weather: fixed city (${esc(w.city || "")})` : `Weather: PC location (${esc(w.city || "finding…")})`}</p>
    ${manual ? '<button class="link" id="d-auto">Use PC location</button>' : ""}
    <input type="text" id="d-city" placeholder="Or a fixed city" value="${manual ? esc(w.city || "") : ""}" autocomplete="off">
    <p style="margin-top:12px">This is the web version of PerfLink. ${STANDALONE ? "" : "Tip: Share → Add to Home Screen opens it like an app."}</p>
    <button class="link red" id="d-disc">Disconnect from PC</button>
    <div class="row"><button class="btn ghost" id="d-cancel">Cancel</button><button class="btn" id="d-save">Save</button></div>`, () => {
    $("d-cancel").onclick = closeDialog;
    $("d-disc").onclick = () => { closeDialog(); disconnect(); };
    if ($("d-auto")) $("d-auto").onclick = () => { send({ cmd: "weather_auto" }); closeDialog(); };
    $("d-save").onclick = () => { const c = $("d-city").value.trim(); if (c) send({ cmd: "set_city", city: c }); closeDialog(); };
  });
}
function waDialog(wa) {
  const now = Date.now() / 1000;
  openDialog(`<h2>${ICON.wa.replace('class="wa-logo"', 'style="width:18px;height:18px;vertical-align:-3px"')} WhatsApp · ${wa.notifications.length} new</h2>
    ${wa.notifications.map((n) => `<div class="note"><b>${esc(n.title)} <span class="dim" style="float:right;font-weight:400">${n.t ? ago(now - n.t) : ""}</span></b>${esc(n.text)}</div>`).join("")}
    <div class="row"><button class="btn" id="d-close">Close</button></div>`, () => { $("d-close").onclick = closeDialog; });
}
let toastTimer = null;
function toast(msg, ok = true) {
  const t = $("toast");
  t.textContent = msg; t.className = "show" + (ok ? "" : " bad");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.className = ""; }, 2500);
}

// ====================================================================== render the page on screen
function render() {
  const s = S.stats; if (!s) return;
  ({ home: renderHome, performance: renderPerformance, overlay: renderOverlay, launcher: renderLauncher,
    processes: renderProcesses, weather: renderWeather, remote: (x) => { $("remote-sub").textContent = `mouse and keyboard for ${x.host || "the PC"}`; } }[page])(s);
}
window.addEventListener("resize", () => { sizeClock(); render(); });

buildTabs();
show(page);
connect();
// for testing in a desktop browser: window.perflink.onMessage({type: "auth", status: "pending", pc: "PC"})
window.perflink = { S, onMessage, connect, disconnect };
