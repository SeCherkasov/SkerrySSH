"use strict";
/*
  Skerry Sync web frontend — the fetch layer and the two credentials it carries.

  The zones never mix: `authGet`/`authDelete` send the account's bearer token, `adminGet`/
  `adminDelete` send X-Admin-Token, and neither function can reach the other's store.

  Where the credentials live:
  - the account's token pair in sessionStorage, so a shared machine forgets it when the tab closes;
  - the admin token in memory only, never written anywhere — reloading the console asks for it again.

  Nothing here decrypts anything. The endpoints it talks to serve metadata and ciphertext sizes.
*/

const SESSION_KEY = "skerry.web.session";

/** Thrown for anything the caller has to show: [status] is 0 when the server never answered. */
class ApiError extends Error {
  constructor(status) { super("api " + status); this.status = status; }
}

/** Set by the app: called when the account session is gone and the page has to fall back to sign-in. */
let onSignedOut = () => {};
const setSignedOutHandler = fn => { onSignedOut = fn; };

function session() {
  try {
    return JSON.parse(sessionStorage.getItem(SESSION_KEY) || "null");
  } catch (e) {
    // Unreadable storage means signed out, but say so somewhere: otherwise the session vanishes
    // with no explanation anyone could act on.
    console.error("session storage is unreadable, treating as signed out", e);
    return null;
  }
}
function storeSession(value) {
  if (value) sessionStorage.setItem(SESSION_KEY, JSON.stringify(value));
  else sessionStorage.removeItem(SESSION_KEY);
}
const accountId = () => (session() || {}).accountId || null;
const hasAccountSession = () => session() !== null;

let adminToken = null;
const hasAdminToken = () => adminToken !== null;
const setAdminToken = value => { adminToken = value; };

/** Reads a JSON body, or null for the 204 the delete endpoints answer with. */
async function body(response) {
  if (response.status === 204) return null;
  try {
    return await response.json();
  } catch (e) {
    // The server did answer — it just answered with something unparsable. Reporting that as a
    // network outage would send whoever debugs it looking in the wrong place.
    console.error("unparsable response from " + response.url, e);
    throw new ApiError(response.status);
  }
}

async function request(path, init) {
  try {
    return await fetch(path, init);
  } catch (e) {
    console.error("request to " + path + " never completed", e);
    throw new ApiError(0);
  }
}

// --- account zone ---

/**
 * A single refresh in flight, shared by everyone who got a 401: a pane firing four requests at once
 * would otherwise rotate the refresh token four times and race itself into a signed-out state.
 */
let refreshing = null;

function refreshSession() {
  if (refreshing) return refreshing;
  const current = session();
  refreshing = (async () => {
    if (!current) return false;
    const response = await request("/auth/refresh", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: current.refreshToken }),
    });
    if (!response.ok) return false;
    const tokens = await response.json();
    storeSession({ accountId: current.accountId, ...tokens });
    return true;
  })().finally(() => { refreshing = null; });
  return refreshing;
}

async function authRequest(path, init, retry) {
  const current = session();
  if (!current) { onSignedOut(); throw new ApiError(401); }
  const response = await request(path, {
    ...init,
    headers: { ...(init || {}).headers, Authorization: "Bearer " + current.accessToken },
  });
  if (response.status === 401) {
    // One refresh, then the sign-in screen: a second 401 means the device was revoked or the
    // refresh token is dead, and retrying past that is a loop, not resilience.
    if (retry && await refreshSession()) return authRequest(path, init, false);
    signOutAccount();
    onSignedOut();
    throw new ApiError(401);
  }
  if (!response.ok) throw new ApiError(response.status);
  return body(response);
}

const authGet = path => authRequest(path, undefined, true);
const authDelete = path => authRequest(path, { method: "DELETE" }, true);

/** Exchanges the web password for the standard token pair. Throws [ApiError] with the raw status. */
async function webLogin(id, password) {
  const response = await request("/auth/web-login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ accountId: id, password: password }),
  });
  if (!response.ok) throw new ApiError(response.status);
  const tokens = await response.json();
  storeSession({ accountId: id, ...tokens });
}

const signOutAccount = () => storeSession(null);

// --- operator zone ---

async function adminRequest(path, init) {
  if (adminToken === null) throw new ApiError(401);
  const response = await request(path, {
    ...init,
    headers: { ...(init || {}).headers, "X-Admin-Token": adminToken },
  });
  if (!response.ok) throw new ApiError(response.status);
  return body(response);
}

const adminGet = path => adminRequest(path, undefined);
const adminDelete = path => adminRequest(path, { method: "DELETE" });

// --- public ---

/** `/admin/health` is the one open endpoint under /admin; the front page reads nothing else. */
async function publicGet(path) {
  const response = await request(path, undefined);
  if (!response.ok) throw new ApiError(response.status);
  return body(response);
}
