import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'OpenDBAPI',
  tagline: 'Turn SQL into governed REST APIs',
  favicon: 'img/favicon.svg',

  future: {v4: true},

  url: 'https://egret-data.github.io',
  baseUrl: '/opendbapi/',
  organizationName: 'egret-data',
  projectName: 'opendbapi',
  trailingSlash: false,

  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'throw',

  // English is canonical; Vietnamese is a reference translation (npm run write-translations -- -l vi).
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'vi'],
    localeConfigs: {en: {label: 'English'}, vi: {label: 'Tiếng Việt'}},
  },

  headTags: [
    {tagName: 'link', attributes: {rel: 'preconnect', href: 'https://fonts.googleapis.com'}},
    {tagName: 'link', attributes: {rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: 'anonymous'}},
  ],
  stylesheets: [
    'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap',
  ],

  themes: [
    [
      '@easyops-cn/docusaurus-search-local',
      {hashed: true, language: ['en'], indexBlog: false, docsRouteBasePath: '/', highlightSearchTermsOnTargetPage: true},
    ],
  ],

  presets: [
    [
      'classic',
      {
        docs: {
          path: 'docs',
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/egret-data/opendbapi/tree/main/docs/',
        },
        blog: false,
        theme: {customCss: './src/css/custom.css'},
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    colorMode: {respectPrefersColorScheme: true},
    navbar: {
      title: 'OpenDBAPI',
      logo: {alt: 'OpenDBAPI', src: 'img/favicon.svg'},
      items: [
        {type: 'docSidebar', sidebarId: 'docs', position: 'left', label: 'Docs'},
        {to: '/reference/admin-api', label: 'API reference', position: 'left'},
        {type: 'localeDropdown', position: 'right'},
        {href: 'https://github.com/egret-data/opendbapi', label: 'GitHub', position: 'right'},
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {label: 'Get started', to: '/'},
            {label: 'Deploy', to: '/deploy/docker'},
            {label: 'API reference', to: '/reference/admin-api'},
          ],
        },
        {
          title: 'Project',
          items: [
            {label: 'GitHub', href: 'https://github.com/egret-data/opendbapi'},
            {label: 'Releases', href: 'https://github.com/egret-data/opendbapi/releases'},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} OpenDBAPI contributors. Apache-2.0.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['bash', 'yaml', 'sql', 'json', 'groovy', 'java'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
