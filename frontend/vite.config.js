import { defineConfig } from 'vite'

// 정적 빌드만 한다. 배포에는 Node 프로세스가 없다 —
// dist/ 를 nginx 가 서빙하고, /api 는 nginx 가 API 서버로 프록시한다.
// (docs/DESIGN.md §11.3)
export default defineConfig({
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    // 로컬 `npm run dev` 일 때만 쓰인다. 운영에서는 nginx 가 같은 일을 한다.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
