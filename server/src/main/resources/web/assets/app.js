"use strict";
/*
  Skerry Sync web frontend — state, routing and the panes.

  The zone comes from the path (`/` public, `/account` cabinet, `/console` operator) and the view
  from the zone plus whether that zone's credential is present. Rendering is synchronous: a pane
  asks `res()` for a path, gets `loading` the first time, and the answer re-renders the page.

  Nothing here decrypts anything, and no list shows a human-readable name the server does not have:
  record and team names live inside the ciphertext.
*/

/** Which team is open inside the Teams tab, and which of its children is shown. */
const team = { id: null, tab: "members" };
/** Which account row is expanded in the operator zone, and which of its children is shown. */
const insp = { acct: null, tab: "devices" };

const TABS = {
  account: [
    { id: "overview", key: "sec.overview" },
    { id: "devices", key: "sec.devices", n: () => count("/devices", d => d.devices.length) },
    { id: "teams", key: "sec.teams", n: () => count("/teams", d => d.teams.length) },
    { id: "sessions", key: "sec.sessions" },
    { id: "storage", key: "sec.storage", n: () => count(STORAGE_PATH, d => d.records.length) },
    { id: "log", key: "sec.log" },
    { id: "security", key: "sec.security" }
  ],
  operator: [
    { id: "stats", key: "sec.stats" },
    // A device belongs to one account (1:N), so it opens inside the account row, not as a sibling tab.
    { id: "accounts", key: "sec.accounts", n: () => count(ACCOUNTS_PATH, d => d.accounts.length) },
    { id: "observ", key: "sec.observ" },
    { id: "audit", key: "sec.audit" }
  ]
};

/** The remembered tab, checked against the zone's tabs: a stale or edited value is not a pane. */
function storedTab(zone) {
  const id = localStorage.getItem("skerry.tab." + zone);
  return TABS[zone].some(x => x.id === id) ? id : TABS[zone][0].id;
}
const state = { tab: { account: storedTab("account"), operator: storedTab("operator") } };

const STORAGE_PATH = "/vault/envelopes?limit=200";
const ACTIVITY_PATH = "/account/activity?limit=100";
const ACCOUNTS_PATH = "/admin/accounts?limit=100";
const ADMIN_ACTIVITY_PATH = "/admin/activity?limit=50";

const el = id => document.getElementById(id);
const enc = encodeURIComponent;
const D_MS = 86400000;
const GLYPH = { Linux: "🐧", Android: "📱", Windows: "🪟", macOS: "🖥", web: "🌐" };
/**
 * A platform label is whatever the device called itself when it enrolled, so it is looked up as an
 * own property: `GLYPH["constructor"]` would otherwise hand a function to the page.
 */
const glyph = platform => (Object.prototype.hasOwnProperty.call(GLYPH, platform) ? GLYPH[platform] : "•");

/* ===== loading ======================================================== */

/**
 * One request per path per render cycle. The first call starts it and returns `loading`; the answer
 * re-renders, and by then the entry is `ready` and the pane draws itself from real data.
 */
const cache = new Map();
function res(key, loader) {
  let entry = cache.get(key);
  if (!entry) {
    entry = { state: "loading" };
    cache.set(key, entry);
    loader().then(
      data => { entry.state = "ready"; entry.data = data; },
      error => {
        // The pane will show the status; the console gets the rest, because a bug in a loader
        // reaches the reader as the same one-line "no answer" a dead network does.
        console.error("loading " + key + " failed", error);
        entry.state = "error";
        entry.error = error;
      },
    ).then(render);
  }
  return entry;
}
/** Value of an already-loaded path, or null — for tab counters, which must not start a request. */
function count(key, of) {
  const entry = cache.get(key);
  return entry && entry.state === "ready" ? of(entry.data) : null;
}
const errText = e => (e && e.status ? t("err.http", { code: e.status }) : t("err.net"));
const pending = entry =>
  '<div class="tablecard"><div class="empty' + (entry.state === "error" ? " bad" : "") + '">' +
  (entry.state === "error" ? esc(errText(entry.error)) : t("state.loading")) + "</div></div>";
const emptyCard = () => '<div class="tablecard"><div class="empty">' + t("ses.empty") + "</div></div>";

/* ===== small builders ================================================= */

const phead = (h, sub) => '<div class="phead"><h2>' + t(h) + "</h2>" +
  (sub ? '<div class="p">' + sub + "</div>" : "") + "</div>";
/** Tile whose label is a literal (an endpoint, an env var) rather than a translation key. */
const tileLit = (label, v, s, cls) => '<div class="tile"><div class="k">' + label + '</div><div class="v ' + (cls || "") + '">' + v + "</div>" +
  '<div class="s">' + (s || "&nbsp;") + "</div></div>";
const tile = (k, v, s, cls) => tileLit(t(k), v, s, cls);

function tablecard(cols, rows) {
  if (!rows.length) return emptyCard();
  return '<div class="tablecard"><table><thead><tr>' +
    cols.map(c => '<th class="' + (c.cls || "") + '">' + (c.key ? t(c.key) : "") + "</th>").join("") +
    "</tr></thead><tbody>" + rows.join("") + "</tbody></table></div>";
}
/** Table nested inside an expanded row: it lives in the parent card's frame, so no chrome of its own. */
function subtable(cols, rows) {
  if (!rows.length) return '<div class="empty">' + t("ses.empty") + "</div>";
  return '<table class="sub"><thead><tr>' +
    cols.map(c => '<th class="' + (c.cls || "") + '">' + (c.key ? t(c.key) : "") + "</th>").join("") +
    "</tr></thead><tbody>" + rows.join("") + "</tbody></table>";
}
const timeline = rows => '<div class="tablecard" style="padding:6px 20px"><div class="tl">' + rows.join("") + "</div></div>";
const tlrow = (at, head, sub) => '<div class="tlrow"><div class="when">' + fmtTime(at) + "</div>" +
  '<div class="body"><div class="t">' + head + '</div><div class="d">' + sub + "</div></div></div>";

/**
 * kotlinx.serialization drops a property that equals its default, so a key epoch of 0 or an empty
 * member count is simply absent from the JSON rather than zero in it. Reading one straight prints
 * NaN — every optional-with-default number from the wire goes through here.
 */
const num = v => (v === undefined || v === null ? 0 : v);

const deviceState = d => d.revoked ? { c: "bad", k: "dev.st.revoked" }
  : (Date.now() - d.lastSeenAt > 7 * D_MS ? { c: "warn", k: "dev.st.stale" } : { c: "ok", k: "dev.st.ok" });
const statusBadge = status => '<span class="badge ' + (status === "active" ? "ok" : "warn") + '">' +
  t(status === "active" ? "team.st.active" : "team.st.invited") + "</span>";

/** Colour of an audit row, by what the event did rather than by which subsystem raised it. */
function eventKind(event) {
  if (event === "account.deleted" || event === "device.revoked") return "bad";
  if (event === "tombstones.purged" || event === "auth.password_changed" ||
      event === "auth.web_password_set" || event === "team.rekey") return "warn";
  if (event.startsWith("sync.push") || event === "auth.register" || event === "device.reenrolled") return "ok";
  if (event.startsWith("auth.") || event.startsWith("team.")) return "cyan";
  return "dim";
}
const eventBadge = event => '<span class="badge ' + eventKind(event) + '">' + esc(event) + "</span>";

/* ===== routing ======================================================== */

function zoneOfPath() {
  const path = location.pathname;
  if (path === "/account" || path.startsWith("/account/")) return "account";
  if (path === "/console" || path.startsWith("/console/")) return "operator";
  return "public";
}
function currentView() {
  const zone = zoneOfPath();
  if (zone === "account") return hasAccountSession() ? "account" : "signin-account";
  if (zone === "operator") return hasAdminToken() ? "operator" : "signin-operator";
  return "public";
}
/** Navigating drops every drill-down and every cached response: a zone is entered fresh. */
function navigate(path) {
  if (location.pathname !== path) history.pushState(null, "", path + location.search);
  cache.clear();
  team.id = null;
  insp.acct = null;
  render();
}
const setTab = (zone, id) => {
  state.tab[zone] = id;
  localStorage.setItem("skerry.tab." + zone, id);
  team.id = null;
  insp.acct = null;   // a tab switch leaves no drill-down open behind it
  render();
};
const openTeam = id => { team.id = id; team.tab = "members"; render(); };
const toggleAccount = id => { insp.acct = insp.acct === id ? null : id; insp.tab = "devices"; render(); };
function signOut(zone) {
  if (zone === "operator") setAdminToken(null); else signOutAccount();
  navigate("/");
}

/* ===== sign-in ======================================================== */

async function submitSignIn(zone) {
  const secret = el("code").value.trim();
  const id = zone === "account" ? el("acct").value.trim() : "";
  const fail = text => { el("err").textContent = text; el("go").disabled = false; };
  if (!secret || (zone === "account" && !id)) { fail(t("gate." + zone + ".err")); return; }
  el("go").disabled = true;
  el("err").textContent = "";
  if (zone === "operator") {
    setAdminToken(secret);
    try {
      // The token is only known to be good once the server has answered with it.
      await adminGet("/admin/stats");
    } catch (e) {
      setAdminToken(null);
      fail(e.status === 401 ? t("gate.operator.err") : errText(e));
      return;
    }
    navigate("/console");
    return;
  }
  try {
    await webLogin(id, secret);
  } catch (e) {
    fail(e.status === 401 ? t("gate.account.err") : e.status === 429 ? t("gate.throttled") : errText(e));
    return;
  }
  navigate("/account");
}


/* ===== destructive actions ============================================ */

/**
 * Every one of these states its blast radius before it runs, and reloads everything afterwards:
 * a revocation changes the device list, the summary counters and the audit log at once.
 */
async function act(run) {
  try {
    await run();
  } catch (e) {
    console.error("action failed", e);
    if (e.status === 401 && zoneOfPath() === "operator") {
      // The token died mid-action. Say so: otherwise the operator is dropped back to the gate with
      // no way to tell whether the delete they confirmed went through.
      setAdminToken(null);
      alert(t("gate.operator.err"));
    } else if (e.status !== 401) {
      alert(errText(e));
    }
  }
  cache.clear();
  render();
}

const revokeDevice = (id, name) => {
  if (!confirm(t("dlg.revoke", { name: name }))) return;
  act(() => authDelete("/devices/" + enc(id)));
};

const adminRevokeDevice = (acct, id, name) => {
  if (!confirm(t("dlg.revoke", { name: name }))) return;
  act(() => adminDelete("/admin/devices/" + enc(id) + "?accountId=" + enc(acct)));
};

const purgeTombstones = acct => {
  if (!confirm(t("dlg.purge", { acct: acct }))) return;
  act(() => adminDelete("/admin/accounts/" + enc(acct) + "/tombstones"));
};

const deleteAccount = acct => {
  if (!confirm(t("dlg.delete", { acct: acct }))) return;
  act(async () => {
    await adminDelete("/admin/accounts/" + enc(acct));
    insp.acct = null;
  });
};

/**
 * Revokes every device of the account, this browser session last — it is the one holding the page.
 *
 * One device per request, so the run can stop halfway; when it does, the screen that follows is the
 * sign-in card, which looks exactly like success. Hence the count: a revocation that left devices
 * signed in has to say so, not let the page imply otherwise.
 */
function signOutEverywhere() {
  if (!confirm(t("dlg.signout"))) return;
  act(async () => {
    const live = (await authGet("/devices")).devices.filter(d => !d.revoked);
    const ordered = live.filter(d => !d.current).concat(live.filter(d => d.current));
    let revoked = 0;
    let failure = null;
    for (const d of ordered) {
      try {
        await authDelete("/devices/" + enc(d.id));
        revoked++;
      } catch (e) {
        failure = e;
        break;
      }
    }
    signOutAccount();
    if (failure) {
      alert(t("dlg.signout.partial", { n: fmtNum(revoked), total: fmtNum(ordered.length) }) + " " + errText(failure));
    }
  });
}

/* ===== render ========================================================= */

function renderTabs() {
  const view = currentView();
  const zone = (view === "account" || view === "operator") ? view : null;
  const tabs = el("tabs");
  if (!zone) { tabs.innerHTML = ""; tabs.style.display = "none"; return; }
  tabs.style.display = "flex";
  tabs.innerHTML = TABS[zone].map(x => {
    const n = x.n ? x.n() : null;
    return '<button class="tab' + (x.id === state.tab[zone] ? " on" : "") + '" data-tab="' + x.id + '">' + t(x.key) +
      (n === null ? "" : '<span class="n">' + fmtNum(n) + "</span>") + "</button>";
  }).join("");
  tabs.querySelectorAll(".tab").forEach(b => b.addEventListener("click", () => setTab(zone, b.dataset.tab)));
}

function renderTopAction() {
  const view = currentView();
  const box = el("topact");
  if (view === "account" || view === "operator") {
    box.innerHTML = '<button class="btn sm" id="reload">' + t("act.refresh") + "</button>" +
      '<button class="btn sm" id="out">' + t(view === "operator" ? "act.lock" : "act.signout") + "</button>";
    box.style.display = "flex";
    box.style.gap = "8px";
    el("reload").addEventListener("click", () => { cache.clear(); render(); });
    el("out").addEventListener("click", () => signOut(view));
  } else if (view === "public") {
    box.innerHTML = '<button class="btn sm" data-go="/account">' + t("act.enter") + "</button>";
  } else {
    box.innerHTML = "";
  }
}

function renderMain() {
  const view = currentView();
  const main = el("main");
  if (view === "public") {
    main.innerHTML = frontPage();
  } else if (view === "signin-account" || view === "signin-operator") {
    const zone = view.slice("signin-".length);
    main.innerHTML = signInPage(zone);
    el("go").addEventListener("click", () => submitSignIn(zone));
    el("back").addEventListener("click", () => navigate("/"));
    main.querySelectorAll("input").forEach(input =>
      input.addEventListener("keydown", e => { if (e.key === "Enter") submitSignIn(zone); }));
    (el("acct") || el("code")).focus();
  } else {
    const pane = PANE[state.tab[view]] || PANE[TABS[view][0].id];
    main.innerHTML = '<div class="wrap pane">' + pane() + "</div>";
  }

  document.querySelectorAll("[data-go]").forEach(b => b.addEventListener("click", () => navigate(b.dataset.go)));
  main.querySelectorAll("tr.pick").forEach(tr =>
    tr.addEventListener("click", () => toggleAccount(tr.dataset.acct)));
  main.querySelectorAll(".itab").forEach(b =>
    b.addEventListener("click", e => {
      e.stopPropagation();
      if (b.dataset.ttab) team.tab = b.dataset.ttab; else insp.tab = b.dataset.itab;
      render();
    }));
  main.querySelectorAll(".panel.team").forEach(c =>
    c.addEventListener("click", () => openTeam(c.dataset.team)));
  main.querySelectorAll("[data-revoke]").forEach(b =>
    b.addEventListener("click", e => { e.stopPropagation(); revokeDevice(b.dataset.revoke, b.dataset.name); }));
  main.querySelectorAll("[data-arevoke]").forEach(b =>
    b.addEventListener("click", e => { e.stopPropagation(); adminRevokeDevice(b.dataset.acct, b.dataset.arevoke, b.dataset.name); }));
  main.querySelectorAll("[data-purge]").forEach(b =>
    b.addEventListener("click", e => { e.stopPropagation(); purgeTombstones(b.dataset.purge); }));
  main.querySelectorAll("[data-delete]").forEach(b =>
    b.addEventListener("click", e => { e.stopPropagation(); deleteAccount(b.dataset.delete); }));
  main.querySelectorAll('[data-action="signout-all"]').forEach(b =>
    b.addEventListener("click", signOutEverywhere));

  const back = el("team-back");
  if (back) back.addEventListener("click", () => { team.id = null; render(); });
  const copy = el("copy");
  if (copy) copy.addEventListener("click", () => {
    // The clipboard API is absent in an insecure context and can be refused in a secure one, so
    // "Copied" is claimed only once the write has actually resolved.
    const write = navigator.clipboard?.writeText(location.origin);
    if (!write) { console.error("clipboard unavailable — the URL was not copied"); return; }
    write.then(
      () => {
        copy.textContent = t("connect.copied");
        setTimeout(() => { copy.textContent = t("connect.copy"); }, 1500);
      },
      e => { console.error("clipboard write refused", e); },
    );
  });
}

function render() {
  renderTabs();
  renderTopAction();
  renderMain();
  const zone = zoneOfPath();
  applyI18n(zone === "operator" ? "title.operator" : zone === "account" ? "title.account" : "title.public");
}

setSignedOutHandler(() => render());
el("home").addEventListener("click", () => navigate("/"));
document.querySelectorAll("[data-lang]").forEach(b => b.addEventListener("click", () => setLang(b.dataset.lang, render)));
window.addEventListener("popstate", () => { cache.clear(); team.id = null; insp.acct = null; render(); });
render();
