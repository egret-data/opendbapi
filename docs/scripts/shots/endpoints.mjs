const open = async (p, BASE, ws, id, tab = '') => {
  await p.goto(`${BASE}/w/${ws}/endpoints/${id}${tab ? `/${tab}` : ''}`);
  await p.getByRole('tab', {name: 'Query'}).waitFor();
};
export const shots = [
  {
    area: 'endpoints', id: 'query',
    run: async (p, {BASE, ws, endpoint}) => { await open(p, BASE, ws, endpoint('v1/customers')); await p.waitForTimeout(1200); },
  },
  {
    area: 'endpoints', id: 'preview',
    run: async (p, {BASE, ws, endpoint}) => {
      await open(p, BASE, ws, endpoint('v1/orders/recent'));
      await p.getByLabel('status', {exact: true}).fill('paid');
      await p.getByRole('button', {name: 'Run'}).click();
      await p.getByRole('tab', {name: 'Result'}).waitFor();
      await p.getByRole('button', {name: 'Run'}).scrollIntoViewIfNeeded();
    },
  },
  {
    area: 'endpoints', id: 'settings',
    run: async (p, {BASE, ws, endpoint}) => { await open(p, BASE, ws, endpoint('v1/customers'), 'settings'); },
  },
  {
    area: 'endpoints', id: 'tests',
    run: async (p, {BASE, ws, endpoint}) => { await open(p, BASE, ws, endpoint('v1/customers'), 'tests'); await p.getByText('gold customers').waitFor(); },
  },
  {
    area: 'endpoints', id: 'versions',
    run: async (p, {BASE, ws, endpoint}) => { await open(p, BASE, ws, endpoint('v1/customers'), 'versions'); await p.getByText('Major versions').waitFor(); },
  },
  {
    area: 'endpoints', id: 'access',
    run: async (p, {BASE, ws, endpoint}) => { await open(p, BASE, ws, endpoint('v1/customers'), 'access'); },
  },
  {
    area: 'endpoints', id: 'workflow-canvas', settle: 1500,
    run: async (p, {BASE, ws, endpoint}) => {
      await open(p, BASE, ws, endpoint('v1/customers/{id}/summary'));
      const canvas = p.getByLabel('Workflow canvas');
      await canvas.waitFor();
      await p.waitForTimeout(800);
      await canvas.getByText('found', {exact: true}).click();
    },
  },
  {
    area: 'endpoints', id: 'try-it',
    run: async (p, {BASE, ws, endpoint}) => { await open(p, BASE, ws, endpoint('v1/orders/recent'), 'try'); },
  },
];
