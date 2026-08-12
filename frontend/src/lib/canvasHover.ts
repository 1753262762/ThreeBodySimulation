import type { BodyState } from '../contracts'

/** 天体悬停信息：物理像素坐标除以 DPR 后作为 CSS 锚点交给 HTML Tooltip。 */
export interface HoverBodyInfo {
  bodyId: string
  bodyState: BodyState
  anchorCssX: number
  anchorCssY: number
}