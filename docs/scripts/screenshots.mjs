// Re-runnable screenshot capture for the docs (see STYLE.md).
// Usage: BASE=http://localhost:8520 node scripts/screenshots.mjs [shot-id ...]
// Point it only at the quick-start stack seeded by scripts/seed-demo.mjs. Output: static/img/<area>/<id>.png, 1440x900, light.
import {chromium} from 'playwright';
import {mkdirSync, readdirSync} from 'node:fs';
import {dirname} from 'node:path';

const shotFiles = readdirSync(new URL('./shots/', import.meta.url)).filter((f) => f.endsWith('.mjs')).sort();
const shots = (await Promise.all(shotFiles.map((f) => import(`./shots/${f}`)))).flatMap((m) => m.shots ?? []);
const BASE = process.env.BASE ?? 'http://localhost:8520';
const PASSWORD = process.env.OPENDBAPI_ADMIN_PASSWORD ?? 'admin-change-me';
const OUT = new URL('../static/img/', import.meta.url).pathname;
const only = new Set(process.argv.slice(2));

const browser = await chromium.launch();
const ctx = await browser.newContext({viewport: {width: 1440, height: 900}, deviceScaleFactor: 1, colorScheme: 'light'});
await ctx.addInitScript(() => {
  localStorage.setItem('opendbapi-theme', 'light');
  localStorage.setItem('opendbapi.language', 'en');
});
const page = await ctx.newPage();

async function signIn() {
  await page.goto(`${BASE}/login`);
  await page.getByLabel('Username').fill('admin');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', {name: 'Sign in'}).click();
  await page.waitForURL((u) => !u.toString().includes('/login'));
}

/** Ids the shots need: endpoint ids by path, service account ids by name. */
async function lookup() {
  const ws = 'demo';
  const endpoints = await (await page.request.get(`${BASE}/admin/api/ws/${ws}/endpoints`)).json();
  const accounts = await (await page.request.get(`${BASE}/admin/api/ws/${ws}/service-accounts`)).json();
  const datasources = await (await page.request.get(`${BASE}/admin/api/ws/${ws}/datasources`)).json();
  return {
    ws,
    endpoint: (path) => endpoints.find((e) => e.path === path).id,
    account: (name) => accounts.find((a) => a.name === name).id,
    datasource: (name) => datasources.find((d) => d.name === name).id,
  };
}

let ids = null;
for (const shot of shots) {
  if (only.size && !only.has(shot.id)) continue;
  if (shot.signedOut) {
    const clean = await browser.newContext({viewport: {width: 1440, height: 900}, colorScheme: 'light'});
    const p = await clean.newPage();
    await shot.run(p, {BASE});
    await save(p, shot);
    await clean.close();
    continue;
  }
  if (!ids) {
    await signIn();
    ids = await lookup();
  }
  await shot.run(page, {BASE, ...ids});
  await page.waitForTimeout(shot.settle ?? 600);
  await save(page, shot);
}
await browser.close();

async function save(p, shot) {
  const file = `${OUT}${shot.area}/${shot.id}.png`;
  mkdirSync(dirname(file), {recursive: true});
  const clip = shot.clip ? await shot.clip(p) : undefined;
  await p.screenshot({path: file, ...(clip ? {clip} : {})});
  console.log(`✓ ${shot.area}/${shot.id}`);
}
