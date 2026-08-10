/**
 * MSW 浏览器启动入口。
 *
 * mock 模式需要先启动 service worker，再等待 ready 以挂载应用，
 * 避免首屏请求在 worker 生效前发出。
 */
import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'
import { createMockWsHandler } from './mockWsServer'

export async function startMock(): Promise<void> {
  const worker = setupWorker(...handlers, createMockWsHandler())
  await worker.start({
    onUnhandledRequest: 'bypass',
    serviceWorker: {
      url: '/mockServiceWorker.js',
    },
  })
}
