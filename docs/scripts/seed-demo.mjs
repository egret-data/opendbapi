// Seeds the "demo" workspace used by the docs' examples and screenshots.
// Usage: BASE=http://localhost:8520 node scripts/seed-demo.mjs
// Expects the quick-start stack (deploy/quickstart) with sample-data.sql loaded. Safe to re-run: it stops if "demo" exists.
const BASE = process.env.BASE ?? 'http://localhost:8520';
const SLUG = process.env.DEMO_SLUG ?? 'demo';
const ADMIN = process.env.OPENDBAPI_ADMIN_PASSWORD ?? 'admin-change-me';
const DB_HOST = process.env.DEMO_DB_HOST ?? 'postgres';
const DB_PORT = Number(process.env.DEMO_DB_PORT ?? 5432);

const jar = new Map();
function cookies() {
  return [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
}
async function call(method, path, body) {
  const headers = {Cookie: cookies(), Accept: 'application/json'};
  if (jar.has('XSRF-TOKEN')) headers['X-XSRF-TOKEN'] = jar.get('XSRF-TOKEN');
  if (body !== undefined) headers['Content-Type'] = typeof body === 'string' ? 'application/yaml' : 'application/json';
  const res = await fetch(BASE + path, {method, headers, body: typeof body === 'string' ? body : body === undefined ? undefined : JSON.stringify(body)});
  for (const c of res.headers.getSetCookie?.() ?? []) {
    const [pair] = c.split(';');
    const i = pair.indexOf('=');
    jar.set(pair.slice(0, i), pair.slice(i + 1));
  }
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text}`);
  return text && res.headers.get('content-type')?.includes('json') ? JSON.parse(text) : text;
}

await call('GET', '/admin/api/auth/providers');
await call('POST', '/admin/api/auth/login', {source: 'local', username: 'admin', password: ADMIN});
const existing = await call('GET', '/admin/api/workspaces');
if (existing.some((w) => w.slug === SLUG)) {
  console.log(`Workspace "${SLUG}" exists; nothing to do.`);
  process.exit(0);
}
const ws = `/admin/api/ws/${SLUG}`;
await call('POST', '/admin/api/workspaces', {slug: SLUG, name: 'Demo shop'});
await call('POST', `${ws}/secrets`, {name: 'shop-reader-password', description: 'Read-only login of the shop database', value: 'shop-reader'});
const ds = await call('POST', `${ws}/datasources`, {
  name: 'shop', description: 'Sample shop database', dialect: 'POSTGRESQL', host: DB_HOST, port: DB_PORT, database: 'opendbapi',
  username: 'shop_reader', password: 'secret:shop-reader-password', properties: {currentSchema: 'shop'}, readOnly: true, pool: {},
});
await call('POST', `${ws}/datasources/${ds.id}:test`, {});
const group = await call('POST', `${ws}/groups`, {name: 'Customers'});

const base = (o) => ({
  params: [], rules: [], pagination: {enabled: false, defaultSize: 20, maxSize: 100, count: true},
  limits: {timeoutSeconds: 30, maxRows: 10000}, cache: {enabled: false, ttlSeconds: 60, scope: 'client'},
  output: {naming: 'as_is', shape: 'list', types: {}, nest: []}, ...o,
});
const sql = (template) => ({type: 'sql', datasourceId: ds.id, transaction: false, statements: [{id: 'main', template}]});
async function endpoint({name, method = 'GET', path, access = 'private', groupId = null, definition, description = null, publish = true, tests = []}) {
  const e = await call('POST', `${ws}/endpoints`, {name, method, path, access, datasourceId: ds.id, groupId});
  await call('PATCH', `${ws}/endpoints/${e.summary.id}`, {name, description, method, path, access, groupId, definition});
  for (const t of tests) await call('POST', `${ws}/endpoints/${e.summary.id}/tests`, t);
  if (publish) await call('POST', `${ws}/endpoints/${e.summary.id}:publish`, {note: 'First version'});
  return e.summary.id;
}

const customers = await endpoint({
  name: 'Customers', path: 'v1/customers', groupId: group.id, description: 'Customers, optionally of one tier, newest first.',
  definition: base({
    params: [{name: 'tier', in: 'query', type: 'string', required: false, enumValues: ['gold', 'silver', 'bronze'], description: 'Only this tier'}],
    pagination: {enabled: true, defaultSize: 20, maxSize: 100, count: true},
    output: {naming: 'camelCase', shape: 'list', types: {}, nest: []},
    task: sql('select id, name, email, country, tier from customers\n<where><if test="tier != null">tier = #{tier}</if></where>\norder by id'),
  }),
  tests: [{name: 'gold customers', params: {tier: 'gold'}, expect: {status: 200, rowCount: {min: 1}}}],
});
const customer = await endpoint({
  name: 'Customer', path: 'v1/customers/{id}', groupId: group.id,
  definition: base({
    params: [{name: 'id', in: 'path', type: 'bigint', required: true}],
    output: {naming: 'camelCase', shape: 'single', types: {}, nest: []},
    task: sql('select id, name, email, country, tier from customers where id = #{id}'),
  }),
});
await endpoint({
  name: 'Recent orders', path: 'v1/orders/recent', access: 'public',
  definition: base({
    params: [{name: 'status', in: 'query', type: 'string', required: false}],
    cache: {enabled: true, ttlSeconds: 60, scope: 'shared'},
    output: {naming: 'camelCase', shape: 'list', types: {total: 'decimal_string'}, nest: []},
    task: sql('select id, customer_id, placed_at, status, total from orders\n<where><if test="status != null">status = #{status}</if></where>\norder by placed_at desc limit 20'),
  }),
});
const v2 = await call('POST', `${ws}/endpoints/${customers}:new-major`, {});
await call('PATCH', `${ws}/endpoints/${v2.summary.id}`, {
  name: v2.summary.name, method: 'GET', path: v2.summary.path, access: 'private', groupId: group.id, description: 'Customers with their order count.',
  definition: base({
    params: [{name: 'tier', in: 'query', type: 'string', required: false}],
    pagination: {enabled: true, defaultSize: 20, maxSize: 100, count: true},
    output: {naming: 'camelCase', shape: 'list', types: {}, nest: []},
    task: sql('select c.id, c.name, c.tier, count(o.id) as orders\nfrom customers c left join orders o on o.customer_id = c.id\n<where><if test="tier != null">c.tier = #{tier}</if></where>\ngroup by c.id order by c.id'),
  }),
});
await call('POST', `${ws}/endpoints/${v2.summary.id}:publish`, {note: 'Adds order counts'});
await call('POST', `${ws}/endpoints/${customers}:deprecate`, {sunset: '2027-06-30T00:00:00Z'});

const customerOrders = await endpoint({
  name: 'Customer orders', path: 'v1/customers/{id}/orders', groupId: group.id,
  definition: base({
    params: [{name: 'id', in: 'path', type: 'bigint', required: true}],
    output: {naming: 'camelCase', shape: 'list', types: {total: 'decimal_string'}, nest: []},
    task: sql('select id, placed_at, status, total from orders where customer_id = #{id} order by placed_at desc limit 5'),
  }),
});
await endpoint({
  name: 'Customer summary', path: 'v1/customers/{id}/summary', groupId: group.id,
  definition: base({
    params: [{name: 'id', in: 'path', type: 'bigint', required: true}],
    task: {type: 'workflow', datasourceId: ds.id, transaction: false, nodes: [
      {id: 'customer', kind: 'sql', template: 'select id, name, tier from customers where id = #{id}', result: 'one', dependsOn: [], x: 0, y: 0},
      {id: 'found', kind: 'decision', condition: 'customer != null', dependsOn: ['customer'], x: 0, y: 150},
      {id: 'orders', kind: 'api', endpointId: customerOrders, params: {id: 'customer.id'}, dependsOn: ['found:yes'], x: -160, y: 300},
      {id: 'answer', kind: 'end', answer: 'orders', dependsOn: ['orders'], x: -160, y: 450},
      {id: 'missing', kind: 'end', answer: 'null', dependsOn: ['found:no'], x: 160, y: 300},
    ]},
  }),
});

const sa = await call('POST', `${ws}/service-accounts`, {name: 'reporting', description: 'Nightly reporting job', disabled: false, attributes: {country: 'VN'}});
await call('PUT', `${ws}/service-accounts/${sa.summary.id}/grants`, {resourceType: 'group', resourceId: group.id});
await call('POST', `${ws}/service-accounts/${sa.summary.id}/clients`, {expiresInDays: 90});
await call('POST', `${ws}/data-policies`, {kind: 'mask', datasourceId: ds.id, column: 'customers.email', mask: {type: 'email'}, exemptServiceAccounts: []});
await call('PUT', `${ws}/environments/prod`, {bindings: {shop: 'shop'}});
for (let i = 0; i < 6; i++) await fetch(`${BASE}/api/${SLUG}/v1/orders/recent${i % 2 ? '?status=paid' : ''}`);
console.log(`Seeded workspace "${SLUG}".`);
