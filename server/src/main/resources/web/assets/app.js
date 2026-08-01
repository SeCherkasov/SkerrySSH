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
    { id: "devices", key: "sec.devices", n: () => count("/devices", () => authGet("/devices"), d => d.devices.filter(x => !x.revoked).length) },
    { id: "teams", key: "sec.teams", n: () => count("/teams", () => authGet("/teams"), d => d.teams.length) },
    { id: "sessions", key: "sec.sessions" },
    { id: "storage", key: "sec.storage", n: () => count(storagePath(), () => authGet(storagePath()), d => d.total) },
    { id: "log", key: "sec.log" },
    { id: "security", key: "sec.security" }
  ],
  operator: [
    { id: "stats", key: "sec.stats" },
    // A device belongs to one account (1:N), so it opens inside the account row, not as a sibling tab.
    { id: "accounts", key: "sec.accounts", n: () => count(accountsPath(), () => adminGet(accountsPath()), d => d.total) },
    { id: "audit", key: "sec.audit" },
    { id: "observ", key: "sec.health" }
  ]
};

const state = { tab: { account: TABS.account[0].id, operator: TABS.operator[0].id } };

/**
 * Paging for every list long enough to need it. The page size is the reader's choice; the offset is
 * where they are. Both live per list, not globally: reading page 4 of the audit log should not move
 * the storage table with it. A stale page after a purge lands on an empty one — the server clamps a
 * too-large offset rather than failing, so nothing here has to guess how long the list still is.
 */
const PAGE_SIZES = [10, 20, 50, 100];
const pager = {
  storage:   { size: PAGE_SIZES[0], page: 0 },
  log:       { size: PAGE_SIZES[0], page: 0 },
  audit:     { size: PAGE_SIZES[0], page: 0 },
  accounts:  { size: PAGE_SIZES[0], page: 0 },
  acctDevs:  { size: PAGE_SIZES[0], page: 0 },
  acctRecs:  { size: PAGE_SIZES[0], page: 0 },
  teamLog:   { size: PAGE_SIZES[0], page: 0 },
};
/** `?limit=&offset=` for one pager, appended to a path that may already carry a query. */
const pageQuery = (key, path) => {
  const p = pager[key];
  return path + (path.includes("?") ? "&" : "?") + "limit=" + p.size + "&offset=" + p.page * p.size;
};
const activityPath = () => pageQuery("log", "/account/activity");
const adminActivityPath = () => pageQuery("audit", "/admin/activity");
const storagePath = () => pageQuery("storage", "/vault/envelopes");
/** A size change restarts at the first page: keeping the offset would land the reader mid-nowhere. */
function setPageSize(key, size) { pager[key].size = size; pager[key].page = 0; render(); }
function setPage(key, page) { pager[key].page = Math.max(0, page); render(); }

/**
 * The control over a paged list: page size, the range on screen, and the two steps. `total` is what
 * the server counted for the whole list, so the range is a fact rather than an estimate.
 */
function pagerBar(key, shown, total) {
  const p = pager[key];
  total = num(total); // one choke point: a missing count must read as zero, never propagate as NaN
  // Nothing on screen reads as 0–0: on a stranded page the first row's ordinal is past the last
  // one's, and "151–150 of 40" is worse than saying plainly that this page holds nothing.
  const from = total === 0 || shown === 0 ? 0 : p.page * p.size + 1;
  const to = shown === 0 ? 0 : p.page * p.size + shown;
  const last = Math.max(0, Math.ceil(total / p.size) - 1);
  const sizes = PAGE_SIZES.map(n =>
    '<button class="psize' + (n === p.size ? " on" : "") + '" data-size="' + key + ":" + n + '">' +
    fmtNum(n) + "</button>").join("");
  const step = (page, glyph, label, off) =>
    '<button class="btn sm ghost pstep" data-page="' + key + ":" + page + '" aria-label="' + label + '"' +
    (off ? " disabled" : "") + ">" + glyph + "</button>";
  return '<div class="pager"><span class="lbl">' + t("page.size") + "</span>" +
    '<div class="psizes">' + sizes + "</div>" +
    '<span class="range">' + t("page.range", { from: fmtNum(from), to: fmtNum(to), total: fmtNum(total) }) + "</span>" +
    step(p.page - 1, "\u2039", t("page.prev"), p.page === 0) +
    step(p.page + 1, "\u203a", t("page.next"), p.page >= last) +
    "</div>";
}
/**
 * The pager for a page that came back empty. Nothing to page through means nothing to draw; a
 * non-empty list behind an empty page means the reader is past its end and needs the way back.
 */
const strandedPager = (key, total) => (num(total) ? pagerBar(key, 0, total) : "");
const accountsPath = () => pageQuery("accounts", "/admin/accounts");
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
/** Latched by a failed draw, released when the reader asks for fresh data. @see res */
let renderFailed = false;
/** Drop every cached response and the failed-draw latch with it: this is the retry. */
const reload = () => { cache.clear(); renderFailed = false; };
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
    // A pane that throws while drawing leaves a rejected promise nobody reads: the page freezes
    // half-rendered and the only trace is the browser console. Say it out loud instead.
    ).then(render).catch(e => {
      console.error("rendering after " + key + " failed", e);
      // One dialog, not one per in-flight request: a broken pane throws on every chain that
      // reaches it, and a stack of identical modals buries the page under its own error.
      if (!renderFailed) { renderFailed = true; alert(t("err.render")); }
    });
  }
  return entry;
}
/**
 * Counter for a tab. It loads its own path rather than peeking at whatever another pane happened to
 * fetch: peeking made the set of numbers in the bar depend on where the reader had already been —
 * Storage counted only after Storage had been opened, and lost the count again on reload.
 */
function count(key, loader, of) {
  const entry = res(key, loader);
  return entry.state === "ready" ? of(entry.data) : null;
}
const errText = e => (e && e.status ? t("err.http", { code: e.status }) : t("err.net"));
const pending = entry =>
  '<div class="tablecard"><div class="empty' + (entry.state === "error" ? " bad" : "") + '">' +
  (entry.state === "error" ? esc(errText(entry.error)) : t("state.loading")) + "</div></div>";
const emptyCard = () => '<div class="tablecard"><div class="empty">' + t("ses.empty") + "</div></div>";

/* ===== small builders ================================================= */

const phead = (h, sub) => '<div class="phead"><h1>' + t(h) + "</h1>" +
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
const tlrow = (at, head, sub) => '<div class="tlrow"><div class="when">' +
  '<span class="day">' + fmtDate(at) + '</span><span class="clock">' + fmtTime(at) + "</span></div>" +
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
  reload();
  team.id = null;
  insp.acct = null;
  resetPages(...Object.keys(pager)); // the next account's lists start at their own first page
  render();
}
const setTab = (zone, id) => {
  state.tab[zone] = id;
  team.id = null;
  insp.acct = null;   // a tab switch leaves no drill-down open behind it
  render();
};
/**
 * A drill-down's pager is per screen, not per subject: opening another team or account with the
 * page number left over from the previous one asks for an offset that subject may not have, and
 * the answer — an empty table — looks like "nothing here" rather than "you are on page 4".
 */
const resetPages = (...keys) => keys.forEach(k => { pager[k].page = 0; });
const openTeam = id => { team.id = id; team.tab = "members"; resetPages("teamLog"); render(); };
const toggleAccount = id => {
  insp.acct = insp.acct === id ? null : id;
  insp.tab = "devices";
  resetPages("acctDevs", "acctRecs");
  render();
};
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
    } else if (e.status === 401) {
      // Same for the account zone: the session is already torn down by the time we get here, and
      // the sign-in card that follows is indistinguishable from having simply been signed out.
      alert(t("err.session"));
    } else {
      alert(errText(e));
    }
  }
  reload();
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
  const nav = el("tabnav");
  if (!zone) { tabs.innerHTML = ""; nav.hidden = true; return; }
  nav.hidden = false;
  tabs.innerHTML = TABS[zone].map(x => {
    const on = x.id === state.tab[zone];
    const n = x.n ? x.n() : null;
    return '<button class="tab' + (on ? " on" : "") + '" role="tab" aria-selected="' + on + '" data-tab="' + x.id + '">' +
      t(x.key) + (n === null || n === undefined ? "" : '<span class="n">' + fmtNum(n) + "</span>") + "</button>";
  }).join("");
  tabs.querySelectorAll(".tab").forEach(b => b.addEventListener("click", () => setTab(zone, b.dataset.tab)));
  // The fade that says "there is more to the right" only belongs there when there is.
  tabs.classList.toggle("more", tabs.scrollWidth > tabs.clientWidth + 1);
}

function renderTopAction() {
  const view = currentView();
  const box = el("topact");
  if (view === "account" || view === "operator") {
    box.innerHTML = '<button class="btn sm" id="reload">' + t("act.refresh") + "</button>" +
      '<button class="btn sm" id="out">' + t(view === "operator" ? "act.lock" : "act.signout") + "</button>";
    box.style.display = "flex";
    box.style.gap = "8px";
    el("reload").addEventListener("click", () => { reload(); render(); });
    el("out").addEventListener("click", () => signOut(view));
  } else {
    // Nothing in the bar on the public page: both entrances are cards below, each labelled with what
    // is behind it. A bare "Enter" up here only asked "enter what?".

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
  main.querySelectorAll("[data-size]").forEach(b =>
    b.addEventListener("click", () => {
      const [key, size] = b.dataset.size.split(":");
      setPageSize(key, Number(size));
    }));
  main.querySelectorAll("[data-page]").forEach(b =>
    b.addEventListener("click", () => {
      const [key, page] = b.dataset.page.split(":");
      setPage(key, Number(page));
    }));

  const back = el("team-back");
  if (back) back.addEventListener("click", () => { team.id = null; render(); });
  const copy = el("copy");
  if (copy) copy.addEventListener("click", () => {
    const flash = key => {
      copy.textContent = t(key);
      setTimeout(() => { copy.textContent = t("connect.copy"); }, 2000);
    };
    /**
     * There is no clipboard API in an insecure context, and a self-hosted instance on a LAN address
     * is exactly that — the common case for this page, not an edge one. Selecting the URL leaves the
     * reader one keystroke from the same result; failing silently left them with nothing at all.
     */
    const manual = () => {
      const url = document.querySelector(".urlrow code");
      if (url) {
        const range = document.createRange();
        range.selectNodeContents(url);
        const sel = getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
      flash("connect.copy.manual");
    };
    // "Copied" is claimed only once the write has actually resolved, never before it.
    const write = navigator.clipboard?.writeText(location.origin);
    if (!write) { manual(); return; }
    write.then(() => flash("connect.copied"), () => manual());
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
window.addEventListener("popstate", () => {
  reload(); team.id = null; insp.acct = null; resetPages(...Object.keys(pager)); render();
});
render();
