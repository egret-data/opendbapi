export const shots = [
  {
    area: 'datasources', id: 'detail',
    run: async (p, {BASE, ws, datasource}) => { await p.goto(`${BASE}/w/${ws}/datasources/${datasource('shop')}`); await p.getByRole('tab', {name: 'Settings'}).waitFor(); await p.waitForTimeout(800); },
  },
  {
    area: 'access', id: 'clients',
    run: async (p, {BASE, ws}) => { await p.goto(`${BASE}/w/${ws}/clients`); await p.getByText('reporting').first().waitFor(); },
  },
  {
    area: 'access', id: 'service-account',
    run: async (p, {BASE, ws, account}) => { await p.goto(`${BASE}/w/${ws}/clients/${account('reporting')}`); await p.getByText('Nightly reporting job').first().waitFor(); },
  },
  {
    area: 'datasources', id: 'tables',
    run: async (p, {BASE, ws, datasource}) => {
      await p.goto(`${BASE}/w/${ws}/datasources/${datasource('shop')}`);
      await p.getByRole('tab', {name: 'Tables'}).click();
      await p.waitForTimeout(1200);
    },
  },
  {
    area: 'access', id: 'data-policies',
    run: async (p, {BASE, ws, datasource}) => {
      await p.goto(`${BASE}/w/${ws}/datasources/${datasource('shop')}`);
      await p.getByRole('tab', {name: 'Masking'}).click();
      await p.getByText('customers.email').first().waitFor();
    },
  },
  {
    area: 'activity', id: 'activity',
    // Cropped above "Top addresses": caller IPs are not for screenshots.
    clip: async () => ({x: 224, y: 56, width: 1216, height: 480}),
    run: async (p, {BASE, ws}) => { await p.goto(`${BASE}/w/${ws}/activity`); await p.waitForTimeout(1500); },
  },
  {
    area: 'workspace', id: 'secrets',
    run: async (p, {BASE, ws}) => { await p.goto(`${BASE}/w/${ws}/settings/secrets`); await p.getByText('secret:shop-reader-password').waitFor(); },
  },
  {
    area: 'workspace', id: 'general',
    run: async (p, {BASE, ws}) => { await p.goto(`${BASE}/w/${ws}/settings`); await p.getByText('Configuration as code').waitFor(); },
  },
  {
    area: 'workspace', id: 'members',
    run: async (p, {BASE, ws}) => { await p.goto(`${BASE}/w/${ws}/settings/members`); await p.waitForTimeout(800); },
  },
  {
    area: 'workspace', id: 'security',
    run: async (p, {BASE, ws}) => { await p.goto(`${BASE}/w/${ws}/settings/security`); await p.waitForTimeout(800); },
  },
  {
    area: 'workspace', id: 'alerts',
    run: async (p, {BASE, ws}) => { await p.goto(`${BASE}/w/${ws}/settings/alerts`); await p.waitForTimeout(800); },
  },
  {
    area: 'config', id: 'apply-yaml',
    run: async (p, {BASE, ws}) => { await p.goto(`${BASE}/w/${ws}/settings/transfer`); await p.getByText('Apply YAML').first().scrollIntoViewIfNeeded(); },
  },
  {
    area: 'config', id: 'api-tokens',
    run: async (p, {BASE, ws}) => {
      await p.goto(`${BASE}/w/${ws}/endpoints`);
      await p.getByRole('button', {name: 'User menu'}).click();
      await p.getByRole('menuitem', {name: 'API tokens'}).click();
      await p.getByRole('button', {name: 'New token'}).waitFor();
    },
  },
];
