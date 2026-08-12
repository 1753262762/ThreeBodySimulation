/**
 * 应用入口。
 *
 * VITE_API_MODE=mock：先启动 MSW 再挂载应用；
 * VITE_API_MODE=live（默认）：直连本地 Java 服务。
 */
import { createApp } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { usePreferencesStore } from './stores/preferences'
import { startMock } from './mocks/browser'
import './styles.css'

const API_MODE = import.meta.env.VITE_API_MODE ?? 'live'

async function bootstrap(): Promise<void> {
  if (API_MODE === 'mock') {
    await startMock()
  }
  const pinia = createPinia()
  setActivePinia(pinia)
  // 主题在 Vue mount 前初始化，减少首屏闪烁并注册 system 监听。
  usePreferencesStore().initializeTheme()
  const app = createApp(App)
  app.use(pinia)
  app.use(router)
  app.mount('#app')
}

void bootstrap()
