/**
 * 应用入口。
 *
 * VITE_API_MODE=mock：先启动 MSW 再挂载应用；
 * VITE_API_MODE=live（默认）：直连本地 Java 服务。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { startMock } from './mocks/browser'
import './styles.css'

const API_MODE = import.meta.env.VITE_API_MODE ?? 'live'

async function bootstrap(): Promise<void> {
  if (API_MODE === 'mock') {
    await startMock()
  }
  const app = createApp(App)
  app.use(createPinia())
  app.use(router)
  app.mount('#app')
}

void bootstrap()