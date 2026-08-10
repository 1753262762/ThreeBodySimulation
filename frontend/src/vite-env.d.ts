/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** mock 使用 MSW 内存模拟；live 连接本地 Java 服务。 */
  readonly VITE_API_MODE?: 'mock' | 'live'
  /** REST 基础路径，默认 /api/v1。 */
  readonly VITE_API_BASE_URL?: string
  /** WebSocket 基础路径，默认与页面同源的 /ws/v1。 */
  readonly VITE_WS_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}