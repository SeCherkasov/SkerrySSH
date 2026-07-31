"use strict";
/*
  Skerry Sync web frontend — the i18n runtime.

  The dictionaries themselves are in dict.js, loaded first: they are data, and keeping them out of
  here leaves the runtime readable. English is the fallback and the source of truth for keys.

  Plurals go through Intl.PluralRules (a naive n === 1 is wrong in Russian for 2, 3, 4 and everything
  ending in them), numbers, dates and relative times through Intl bound to the active language.
*/

/** ?lang= wins (shareable link), then the stored preference, then the browser, then English. */
function pickLang() {
  const q = new URLSearchParams(location.search).get("lang");
  if (q && LANGS.includes(q)) return q;
  const saved = localStorage.getItem(LANG_KEY);
  if (saved && LANGS.includes(saved)) return saved;
  for (const tag of (navigator.languages || [navigator.language || "en"])) {
    const primary = String(tag).toLowerCase().split("-")[0];
    if (LANGS.includes(primary)) return primary;
  }
  return "en";
}

let lang = pickLang();

/** Missing translation degrades to English, never to a raw key. */
function entry(key) {
  const d = DICT[lang];
  return (d && d[key] !== undefined) ? d[key] : DICT.en[key];
}
function t(key, vars) {
  let s = entry(key);
  if (s === undefined) return key;
  if (typeof s === "object") s = s.other;
  return vars ? String(s).replace(/\{(\w+)\}/g, (m, k) => (vars[k] !== undefined ? vars[k] : m)) : String(s);
}
/** CLDR plural category, not `n === 1`: Russian needs one/few/many, Chinese only other. */
function tn(key, n) {
  const forms = entry(key);
  const cat = new Intl.PluralRules(lang).select(n);
  const form = (forms && (forms[cat] || forms.other)) || "{n}";
  return form.replace("{n}", fmtNum(n));
}

const fmtNum = n => new Intl.NumberFormat(lang).format(n);
function fmtBytes(n) {
  if (n < 1024) return fmtNum(n) + " " + t("unit.b");
  const f = new Intl.NumberFormat(lang, { maximumFractionDigits: 1 });
  if (n < 1048576) return f.format(n / 1024) + " " + t("unit.kib");
  return f.format(n / 1048576) + " " + t("unit.mib");
}
const fmtDate = ms => new Intl.DateTimeFormat(lang, { year: "2-digit", month: "short", day: "2-digit" }).format(new Date(ms));
const fmtTime = ms => new Intl.DateTimeFormat(lang, { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(ms));
/** Relative time through Intl, replacing the English-only helper the old console had. */
function fmtAgo(ms) {
  const diff = ms - Date.now();
  const abs = Math.abs(diff);
  if (abs < 60000) return t("t.now");
  const rtf = new Intl.RelativeTimeFormat(lang, { numeric: "auto" });
  for (const [unit, span] of [["day", 86400000], ["hour", 3600000], ["minute", 60000]]) {
    if (abs >= span) return rtf.format(Math.round(diff / span), unit);
  }
  return t("t.now");
}

/**
 * One pass over the document: [data-i18n] text, [data-i18n-attr="placeholder:key;title:key"]
 * attributes, <html lang> and the title. Called on load and on every language switch.
 */
function applyI18n(titleKey, root) {
  const scope = root || document;
  scope.querySelectorAll("[data-i18n]").forEach(el => { el.textContent = t(el.dataset.i18n); });
  scope.querySelectorAll("[data-i18n-attr]").forEach(el => {
    el.dataset.i18nAttr.split(";").filter(Boolean).forEach(pair => {
      const [attr, key] = pair.split(":");
      el.setAttribute(attr.trim(), t(key.trim()));
    });
  });
  document.documentElement.lang = lang;
  if (titleKey) document.title = t(titleKey);
  document.querySelectorAll("[data-lang]").forEach(b => b.classList.toggle("on", b.dataset.lang === lang));
}

/** Switch, persist, keep the URL shareable, then let the page re-render. */
function setLang(next, onChange) {
  if (!LANGS.includes(next) || next === lang) return;
  lang = next;
  localStorage.setItem(LANG_KEY, lang);
  const url = new URL(location.href);
  url.searchParams.set("lang", lang);
  history.replaceState(null, "", url);
  if (onChange) onChange();
}

const esc = s => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
