"use strict";
/*
  Skerry Sync web frontend — the panes: what each screen is made of.

  Markup only, in the sense that matters here: a pane asks `res()` for the paths it needs, renders a
  placeholder for anything not ready yet, and returns a string. State, routing, the loading cache and
  every builder it calls live in app.js, which is loaded after this file and runs the first render.

  Zero-knowledge holds throughout: ids, types, sizes, timestamps and a ciphertext preview — never a
  human-readable name the server does not have, and never a record blob.
*/

/* ===== public front page ============================================== */

function frontPage() {
  const health = res("/admin/health", () => publicGet("/admin/health"));
  const waiting = health.state === "loading";
  const up = health.state === "ready" && health.data.status === "ok";
  const unknown = "—";
  const version = health.state === "ready" ? esc(health.data.version) : unknown;
  const registration = health.state === "ready"
    ? t(health.data.registrationOpen ? "instance.reg.open" : "instance.reg.closed")
    : unknown;
  // The scheme is printed verbatim, like every other API value: the page cannot know the TLS
  // version, and a "TLS 1.3" it did not measure would be decoration, not a fact. Plain http is not
  // a neutral one of two schemes — tokens and wrapped keys cross the wire readable — so it is
  // marked, except on a loopback host, where there is no wire to read.
  const tls = location.protocol === "https:";
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname);
  const transport = '<span class="mono">' + location.protocol.replace(":", "") + "</span>";

  return '<div class="wrap front">' +
    // Three states, not two: until the answer arrives the instance is unknown, and a red
    // "Unavailable" on every first paint is a claim the page has not checked.
    '<div class="kicker' + (waiting ? " wait" : up ? "" : " down") + '"><span class="dot"></span>' +
      (waiting ? t("state.loading") : t(up ? "instance.status.up" : "instance.status.down")) + "</div>" +
    "<h1>" + esc(location.host) + "</h1>" +
    '<div class="lead">' + t("front.lead") + "</div>" +

    '<div class="facts">' +
      '<div class="fact"><div class="k">' + t("instance.storage") + '</div>' +
        '<div class="v ok">' + t("instance.storage.val") + "</div></div>" +
      '<div class="fact"><div class="k">' + t("instance.version") + '</div><div class="v">' + version + "</div></div>" +
      '<div class="fact"><div class="k">' + t("instance.reg") + '</div><div class="v">' + registration + "</div></div>" +
      '<div class="fact"><div class="k">' + t("instance.transport") + '</div>' +
        '<div class="v' + (tls || loopback ? "" : " warn") + '">' + transport + "</div>" +
        (tls || loopback ? "" : '<div class="s warn">' + t("instance.transport.plain") + "</div>") + "</div>" +
    "</div>" +

    '<div class="seclabel">' + t("front.doors") + "</div>" +
    '<div class="doors">' +
      '<button class="door" data-go="/account"><div class="t">' + t("zone.account") + '</div>' +
        '<div class="d">' + t("zone.account.d") + '</div><div class="go">' + t("act.enter") + " →</div></button>" +
      '<button class="door" data-go="/console"><div class="t">' + t("zone.operator") + '</div>' +
        '<div class="d">' + t("zone.operator.d") + '</div><div class="go">' + t("act.enter") + " →</div></button>" +
    "</div>" +

    '<div class="urlrow"><span class="lbl">' + t("connect.url") + "</span><code>" + esc(location.origin) + "</code>" +
      '<button class="btn sm" id="copy" data-i18n="connect.copy"></button></div>' +
    '<div class="steps">' + ["s1", "s2", "s3"].map(s =>
      '<div class="step"><div class="n"></div><div class="t">' + t("connect." + s) + "</div>" +
      '<div class="d">' + t("connect." + s + "d") + "</div></div>").join("") + "</div>" +

    '<div class="foot"><span>Skerry Sync ' + version + " · AGPL-3.0</span></div></div>";
}

/* ===== sign-in ======================================================== */

function signInPage(zone) {
  const icon = '<div class="ico"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="#2BBDEE" stroke-width="1.7">' +
    '<rect x="4" y="10" width="16" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg></div>';
  if (zone === "operator") {
    return '<div class="wrap"><div class="signin">' + icon +
      "<h1>" + t("gate.operator.h") + "</h1><p>" + t("gate.operator.p") + "</p>" +
      '<input id="code" type="password" data-i18n-attr="placeholder:gate.operator.ph;aria-label:gate.operator.ph" autocomplete="off"/>' +
      '<button class="btn primary" id="go">' + t("gate.operator.go") + "</button>" +
      '<button class="btn ghost" id="back" style="width:100%;margin-top:8px">' + t("gate.back") + "</button>" +
      '<div class="err" id="err" role="alert" aria-live="polite"></div></div></div>';
  }
  // Account: account id + web password. The web password is a server-side credential set in the app;
  // it is not the master password and never touches the vault keys.
  return '<div class="wrap"><div class="signin">' + icon +
    "<h1>" + t("gate.account.h") + "</h1><p>" + t("gate.account.p") + "</p>" +
    '<input id="acct" type="email" class="plain" data-i18n-attr="placeholder:gate.acct.ph;aria-label:gate.acct.label" autocomplete="username"/>' +
    '<input id="code" type="password" class="plain" style="margin-top:10px" data-i18n-attr="placeholder:gate.pw.ph;aria-label:gate.pw.ph" autocomplete="current-password"/>' +
    '<button class="btn primary" id="go">' + t("gate.account.go") + "</button>" +
    '<button class="btn ghost" id="back" style="width:100%;margin-top:8px">' + t("gate.back") + "</button>" +
    '<div class="err" id="err" role="alert" aria-live="polite"></div>' +
    '<div class="hint">' + t("gate.account.hint") + "</div></div></div>";
}

/* ===== account zone =================================================== */

function deviceRows() {
  const r = res("/devices", () => authGet("/devices"));
  if (r.state !== "ready") return pending(r);
  const revoked = r.data.devices.filter(d => d.revoked).length;
  const devices = r.data.devices.filter(d => !d.revoked);
  if (!devices.length) return emptyCard();
  const foot = revoked ? '<div class="note">' + tn("dev.revoked.hidden", revoked) + "</div>" : "";
  return '<div class="rows">' + devices.map(d => {
    const st = deviceState(d);
    const platform = d.platform ? esc(d.platform) : "—";
    return '<div class="row"><div class="glyph">' + glyph(d.platform) + "</div>" +
      '<div class="main"><div class="t">' + esc(d.name) +
        (d.current ? '<span class="badge cyan">' + t("dev.current") + "</span>" : "") + "</div>" +
      '<div class="d">' + platform + " · " + t("dev.created") + " " + fmtDate(d.createdAt) +
        " · " + t("dev.seen") + " " + fmtAgo(d.lastSeenAt) + "</div></div>" +
      '<div class="side"><span class="badge ' + st.c + '">' + t(st.k) + "</span>" +
        '<button class="btn sm danger" data-revoke="' + esc(d.id) +
          '" data-name="' + esc(d.name) + '">' + t("act.revoke") + "</button></div></div>";
  }).join("") + "</div>" + foot;
}

/** Live sessions are per team, so the team list has to arrive before any share list can be asked for. */
function shareRows() {
  const teams = res("/teams", () => authGet("/teams"));
  if (teams.state !== "ready") return pending(teams);
  const active = teams.data.teams.filter(x => x.status === "active");
  const lists = active.map(x => ({ team: x.id, r: res("/teams/" + enc(x.id) + "/shares", () => authGet("/teams/" + enc(x.id) + "/shares")) }));
  const failed = lists.find(l => l.r.state === "error");
  if (failed) return pending(failed.r);
  if (lists.some(l => l.r.state !== "ready")) return pending({ state: "loading" });
  const shares = lists.flatMap(l => l.r.data.shares.map(s => ({ ...s, team: l.team })));
  if (!shares.length) return emptyCard();
  return '<div class="rows">' + shares.map(s =>
    '<div class="row"><div class="glyph">▶</div><div class="main">' +
    '<div class="t">' + esc(s.hostAccountId) + '<span class="badge dim mono">' + esc(s.team) + "</span></div>" +
    '<div class="d">' + t("ses.started") + " " + fmtAgo(s.startedAt) + "</div></div>" +
    '<div class="side"><span class="badge cyan">' + tn("n.viewers", s.viewers) + "</span></div></div>").join("") + "</div>";
}

const envelopeCols = [
  { key: "st.id" }, { key: "st.type" }, { key: "st.ver", cls: "num" }, { key: "st.size", cls: "num" },
  { key: "st.device" }, { key: "st.preview" },
];
const envelopeRow = r => "<tr>" +
  '<td class="id mono">' + esc(r.id) + '</td><td><span class="badge dim">' + esc(r.type) + "</span></td>" +
  '<td class="num mono">v' + r.version + '</td><td class="num">' + fmtBytes(r.blobBytes) + "</td>" +
  "<td>" + esc(r.deviceId) + '</td><td class="hex">' + esc(r.previewHex) + " …</td></tr>";

const PANE = {
  overview() {
    const head = phead("ov.h", esc(accountId() || ""));
    const r = res("/account/summary", () => authGet("/account/summary"));
    if (r.state !== "ready") return head + pending(r);
    const s = r.data;
    return head +
      '<div class="tiles">' +
        tile("ov.devices", fmtNum(s.devices), t("ov.devices.sub", { n: fmtNum(s.activeDevices) })) +
        tile("ov.records", fmtNum(s.records), t("ov.records.sub", { n: fmtNum(s.tombstones) })) +
        tile("ov.storage", fmtBytes(s.storageBytes), "XChaCha20-Poly1305", "sm") +
        tile("ov.lastsync", s.lastSeenAt ? fmtAgo(s.lastSeenAt) : "—", s.lastSeenAt ? fmtTime(s.lastSeenAt) : "", "sm ok") +
      "</div>";
  },

  devices: () => phead("dev.h", t("dev.p")) + deviceRows(),

  teams() {
    const r = res("/teams", () => authGet("/teams"));
    if (r.state !== "ready") return phead("team.h", t("team.p")) + pending(r);
    const teams = r.data.teams;
    if (team.id) {
      const open = teams.find(x => x.id === team.id);
      if (open) return PANE.teamDetail(open);
      team.id = null;
    }
    if (!teams.length) return phead("team.h", t("team.p")) + emptyCard();
    return phead("team.h", t("team.p")) +
      '<div class="grid2">' + teams.map(x =>
        '<div class="panel team" data-team="' + esc(x.id) + '"><div class="t mono">' + esc(x.id) + " " +
          statusBadge(x.status) + "</div>" +
        '<div class="d">' + t("team.role") + ": " + esc(x.role) + "</div>" +
        '<div class="meta"><div>' + t("team.members") + "<b>" + tn("n.members", x.memberCount) + "</b></div>" +
          "<div>" + t("team.epoch") + "<b>v" + fmtNum(num(x.keyEpoch)) + "</b></div>" +
          '<div style="margin-left:auto;align-self:flex-end"><button class="btn sm">' + t("act.open") + "</button></div></div></div>").join("") +
      "</div>";
  },

  /**
   * One team, read-only. Membership and keys are managed in the app: an invite or a key rotation
   * seals an envelope under the team key, and the browser has no key to seal it with.
   */
  teamDetail(x) {
    const base = "/teams/" + enc(x.id);
    const audit = x.role === "owner" || x.role === "admin";
    const tab = (id, key, n) => '<button class="itab' + (team.tab === id ? " on" : "") + '" data-ttab="' + id + '">' +
      t(key) + (n === null ? "" : '<span class="n">' + fmtNum(n) + "</span>") + "</button>";
    if (team.tab === "activity" && !audit) team.tab = "members";

    const members = res(base + "/members", () => authGet(base + "/members"));
    const scopes = res(base + "/scopes", () => authGet(base + "/scopes"));
    const activityPathTeam = pageQuery("teamLog", base + "/activity");
    const activity = audit ? res(activityPathTeam, () => authGet(activityPathTeam)) : null;
    const source = team.tab === "members" ? members : team.tab === "scopes" ? scopes : activity;

    let body;
    if (source.state !== "ready") {
      body = pending(source);
    } else if (team.tab === "members") {
      body = tablecard(
        [{ key: "team.acct" }, { key: "team.role" }, { key: "team.state" }, { key: "team.since" }],
        source.data.members.map(m => "<tr>" +
          '<td class="id">' + esc(m.accountId) + "</td><td>" + esc(m.role) + "</td>" +
          "<td>" + statusBadge(m.status) + "</td><td>" + fmtDate(m.createdAt) + "</td></tr>"));
    } else if (team.tab === "scopes") {
      body = tablecard(
        [{ key: "team.scope" }, { key: "team.grants", cls: "num" }, { key: "team.epoch", cls: "num" }],
        source.data.scopes.map(sc => "<tr>" +
          '<td class="id mono">' + esc(sc.scopeId) + '</td><td class="num">' + tn("n.members", num(sc.memberCount)) + "</td>" +
          '<td class="num mono">v' + fmtNum(num(sc.keyEpoch)) + "</td></tr>")) +
        '<div class="note">' + t("team.scope.p") + "</div>";
    } else {
      body = pagerBar("teamLog", source.data.entries.length, source.data.total) +
        timeline(source.data.entries.map(e => tlrow(
        e.createdAt,
        eventBadge(e.event) + " " + esc(e.detail),
        t("team.act.who") + ": " + esc(e.actorAccountId) + " · " + fmtAgo(e.createdAt))));
    }

    return '<button class="btn sm ghost" id="team-back">← ' + t("team.back") + "</button>" +
      '<div class="dhead"><h1 class="mono">' + esc(x.id) + "</h1>" + statusBadge(x.status) +
      '<span class="badge dim">' + esc(x.role) + "</span>" +
      '<span class="dmeta">' + tn("n.members", x.memberCount) + " · " + t("team.epoch") + " v" + fmtNum(num(x.keyEpoch)) + "</span></div>" +
      '<div class="itabs flat">' +
        tab("members", "team.members", count(base + "/members", () => authGet(base + "/members"), d => d.members.length)) +
        tab("scopes", "team.scopes", count(base + "/scopes", () => authGet(base + "/scopes"), d => d.scopes.length)) +
        (audit ? tab("activity", "team.log", count(activityPathTeam, () => authGet(activityPathTeam), d => d.total)) : "") +
      "</div>" + body;
  },

  sessions: () => phead("ses.h", t("ses.p")) + shareRows(),

  storage() {
    const head = phead("st.h", t("st.p"));
    const path = storagePath();
    const r = res(path, () => authGet(path));
    if (r.state !== "ready") return head + pending(r);
    if (!r.data.records.length) return head + strandedPager("storage", r.data.total) + emptyCard();
    return head + pagerBar("storage", r.data.records.length, r.data.total) +
      tablecard(envelopeCols, r.data.records.map(envelopeRow)) +
      '<div class="note">' + t("st.note") + "</div>";
  },

  log() {
    const head = phead("log.h", t("log.p"));
    const path = activityPath();
    const r = res(path, () => authGet(path));
    if (r.state !== "ready") return head + pending(r);
    // An empty page keeps its pager when the list itself is not empty: the reader is standing past
    // the end (rows were purged under them, or the page size shrank) and "‹" is the only way back.
    if (!r.data.events.length) return head + strandedPager("log", r.data.total) + emptyCard();
    return head + pagerBar("log", r.data.events.length, r.data.total) + timeline(r.data.events.map(e => tlrow(
      e.createdAt,
      eventBadge(e.event) + " " + esc(e.detail),
      t("log.device") + ": " + (e.deviceId ? esc(e.deviceId) : "—") + " · " + fmtAgo(e.createdAt))));
  },

  /**
   * Two of these rows are hand-offs, not controls: both passwords are set in the app, and the page
   * says where rather than offering a button that cannot do it.
   */
  security() {
    const item = (k, action) =>
      '<div class="row" style="align-items:flex-start"><div class="main">' +
      '<div class="t">' + t(k) + '</div><div class="d" style="max-width:64ch;line-height:1.6">' + t(k + "d") + "</div></div>" +
      (action ? '<div class="side"><button class="btn sm" data-action="' + action + '">' + t(k + ".go") + "</button></div>" : "") +
      "</div>";
    return phead("sc.h") + '<div class="rows">' + item("sc.all", "signout-all") + "</div>";
  },

  /* ---- operator ---- */

  stats() {
    const head = phead("op.h", t("op.p"));
    const r = res("/admin/stats", () => adminGet("/admin/stats"));
    if (r.state !== "ready") return head + pending(r);
    const s = r.data;
    return head +
      '<div class="tiles">' +
        tile("op.accounts", fmtNum(s.accounts)) +
        tile("op.devices", fmtNum(s.devices)) +
        tile("op.records", fmtNum(s.records)) +
        tile("op.storage", fmtBytes(s.storageBytes), "", "sm") +
      "</div>";
  },

  /** Accounts, each row expanding in place into its devices and its record envelopes. */
  accounts() {
    const head = phead("op.acc.h", t("op.acc.p"));
    const r = res(accountsPath(), () => adminGet(accountsPath()));
    if (r.state !== "ready") return head + pending(r);
    const cols = [{ key: "op.acc.id" }, { key: "op.acc.created" }, { key: "op.acc.devices", cls: "num" },
                  { key: "op.acc.records", cls: "num" }, { key: "op.acc.size", cls: "num" },
                  { key: "op.acc.seen" }, { key: "", cls: "num" }];
    if (!r.data.accounts.length) return head + strandedPager("accounts", r.data.total) + emptyCard();
    const rows = r.data.accounts.map(a => {
      const open = insp.acct === a.id;
      const row = '<tr class="pick' + (open ? " selected" : "") + '" data-acct="' + esc(a.id) + '">' +
        '<td class="id"><span class="caret">▸</span>' + esc(a.id) + "</td>" +
        "<td>" + fmtDate(a.createdAt) + "</td>" +
        '<td class="num">' + fmtNum(a.devices) + '</td><td class="num">' + fmtNum(a.records) + "</td>" +
        '<td class="num">' + fmtBytes(a.storageBytes) + "</td>" +
        "<td>" + (a.lastSeenAt ? fmtAgo(a.lastSeenAt) : "—") + "</td>" +
        '<td class="num"><button class="btn sm">' + t(open ? "act.close" : "act.open") + "</button></td></tr>";
      return open
        ? row + '<tr class="exp"><td class="expcell" colspan="' + cols.length + '">' + PANE.expand(a) + "</td></tr>"
        : row;
    });
    return head + pagerBar("accounts", r.data.accounts.length, r.data.total) +
      '<div class="tablecard"><table><thead><tr>' +
      cols.map(c => '<th class="' + (c.cls || "") + '">' + (c.key ? t(c.key) : "") + "</th>").join("") +
      "</tr></thead><tbody>" + rows.join("") + "</tbody></table></div>";
  },

  /** Both 1:N children of an account, as tabs inside its row. */
  expand(a) {
    const devicesPath = pageQuery("acctDevs", "/admin/devices?accountId=" + enc(a.id));
    const recordsPath = pageQuery("acctRecs", "/admin/accounts/" + enc(a.id) + "/records");
    const devices = res(devicesPath, () => adminGet(devicesPath));
    const records = res(recordsPath, () => adminGet(recordsPath));
    const source = insp.tab === "devices" ? devices : records;
    const tab = (id, key, n) => '<button class="itab' + (insp.tab === id ? " on" : "") + '" data-itab="' + id + '">' +
      t(key) + (n === null ? "" : '<span class="n">' + fmtNum(n) + "</span>") + "</button>";

    let body;
    if (source.state !== "ready") {
      body = '<div class="empty' + (source.state === "error" ? " bad" : "") + '">' +
        (source.state === "error" ? esc(errText(source.error)) : t("state.loading")) + "</div>";
    } else if (insp.tab === "devices") {
      // /admin/devices lists active devices only, so a revoked one leaves this table rather than
      // sitting in it greyed out.
      body = pagerBar("acctDevs", source.data.devices.length, source.data.total) + subtable(
        [{ key: "dev.name" }, { key: "dev.platform" }, { key: "dev.created" }, { key: "dev.seen" },
         { key: "dev.cursor", cls: "num" }, { key: "dev.state" }, { key: "", cls: "num" }],
        source.data.devices.map(d => {
          const st = deviceState(d);
          return "<tr>" +
            '<td class="id">' + esc(d.name) + "</td><td>" + (d.platform ? esc(d.platform) : "—") + "</td>" +
            "<td>" + fmtDate(d.createdAt) + "</td><td>" + fmtAgo(d.lastSeenAt) + "</td>" +
            '<td class="num mono">' + (d.syncVersion === null ? "—" : fmtNum(d.syncVersion)) + "</td>" +
            '<td><span class="badge ' + st.c + '">' + t(st.k) + "</span></td>" +
            '<td class="num">' + (d.revoked ? "" : '<button class="btn sm danger" data-arevoke="' + esc(d.id) +
              '" data-acct="' + esc(a.id) + '" data-name="' + esc(d.name) + '">' + t("act.revoke") + "</button>") +
            "</td></tr>";
        }));
    } else {
      body = pagerBar("acctRecs", source.data.records.length, source.data.total) +
        subtable(envelopeCols, source.data.records.map(envelopeRow));
    }

    return '<div class="expand"><div class="itabs">' +
      tab("devices", "sec.devices", count(devicesPath, () => adminGet(devicesPath), d => d.total)) +
      tab("records", "sec.storage", count(recordsPath, () => adminGet(recordsPath), d => d.total)) +
      '<div class="iacts"><button class="btn sm" data-purge="' + esc(a.id) + '">' + t("act.purge") + "</button>" +
      '<button class="btn sm danger" data-delete="' + esc(a.id) + '">' + t("act.delete") + "</button></div></div>" +
      body + "</div>";
  },

  observ() {
    const head = phead("op.observ.h");
    const r = res("/admin/observability", () => adminGet("/admin/observability"));
    if (r.state !== "ready") return head + pending(r);
    const o = r.data;
    // Endpoints, env vars and API values print verbatim: the operator compares this against curl
    // and the .env, so a translated "ready" would be a divergence, not a courtesy.
    const age = o.inventoryAgeSeconds;
    const stale = age !== null && age !== undefined && o.inventoryIntervalSeconds > 0 &&
      age > o.inventoryIntervalSeconds * 2;
    return head + '<div class="tiles" style="grid-template-columns:repeat(3,1fr)">' +
      tileLit('<span class="mono">/metrics</span>', '<span class="mono">' + esc(o.metrics) + "</span>",
        "SKERRY_METRICS", "sm " + (o.metrics === "off" ? "" : o.metrics === "open" ? "warn" : "ok")) +
      tileLit('<span class="mono">/readyz</span>',
        '<span class="mono">' + (o.ready ? "ready" : "not_ready") + "</span>",
        t("op.observ.db") + ": " + t(o.ready ? "instance.status.up" : "instance.status.down"), "sm " + (o.ready ? "ok" : "bad")) +
      tileLit('<span class="mono envkey">SKERRY_METRICS_INVENTORY_SECONDS</span>',
        '<span class="mono">' + fmtNum(o.inventoryIntervalSeconds) + "</span>",
        t("op.observ.age") + ": " + (age === null || age === undefined ? t("op.observ.never") : fmtNum(age) + " s"),
        "sm " + (stale ? "warn" : "")) +
      "</div>";
  },

  audit: () => phead("op.audit.h") + PANE.auditTable(),

  auditTable() {
    const path = adminActivityPath();
    const r = res(path, () => adminGet(path));
    if (r.state !== "ready") return pending(r);
    return pagerBar("audit", r.data.events.length, r.data.total) + tablecard(
      [{ key: "log.when" }, { key: "op.acct" }, { key: "log.device" }, { key: "log.event" }, { key: "log.detail" }],
      r.data.events.map(e => "<tr>" +
        '<td class="mono">' + fmtDateTime(e.createdAt) + '</td><td class="id">' + esc(e.accountId) + "</td>" +
        "<td>" + (e.deviceId ? esc(e.deviceId) : "—") + "</td><td>" + eventBadge(e.event) + "</td>" +
        "<td>" + esc(e.detail) + "</td></tr>"));
  }
};
