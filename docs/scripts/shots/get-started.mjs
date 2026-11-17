export const shots = [
  {
    area: 'get-started', id: 'login', signedOut: true,
    run: async (p, {BASE}) => { await p.goto(`${BASE}/login`); await p.getByLabel('Username').waitFor(); },
  },
  {
    area: 'get-started', id: 'endpoints-list',
    run: async (p, {BASE, ws}) => { await p.goto(`${BASE}/w/${ws}/endpoints`); await p.getByText('Recent orders').first().waitFor(); },
  },
];
