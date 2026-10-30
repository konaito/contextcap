import { defineConfig } from 'void/config';

export default defineConfig({
  inference: {
    appType: 'static',
    outputDir: 'site',
  },
  routing: {
    headers: {
      '/skills.md': ['Content-Type: text/markdown; charset=utf-8'],
      '/skill.md': ['Content-Type: text/markdown; charset=utf-8'],
      '/llms.txt': ['Content-Type: text/plain; charset=utf-8'],
    },
  },
  cloudflare: {
    name: 'contextcap-lp',
    account_id: '9b67e2ec0e2cf3359289e1fc860d3777',
    routes: [{ pattern: 'contextcap.fybe.jp', custom_domain: true }],
  },
});
