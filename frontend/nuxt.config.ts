export default defineNuxtConfig({
  compatibilityDate: '2025-01-01',
  devtools: { enabled: false },
  telemetry: false,
  devServer: {
    host: '127.0.0.1',
    port: 3000
  },
  nitro: {
    host: '127.0.0.1',
    port: 3000
  }
})
