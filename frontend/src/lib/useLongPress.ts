/**
 * useLongPress：长按连续触发 composable。
 *
 * 按下后经 delayMs（默认 350 ms）进入长按模式，之后按 tickMs 周期调用
 * onStep，倍速随按住时长递增（默认 1×/2×/4×/8×，8× 封顶），供时间轴
 * 跳转复用。pointerup / pointercancel / 窗口 blur / document 隐藏 / 卸载
 * 均会清理定时器，避免泄漏。未达延迟即松手视为一次单击（onTap）。
 */
import { ref } from 'vue'

export interface LongPressOptions {
  /** 长按触发延迟，默认 350 ms。 */
  delayMs?: number
  /** 进入长按后的重复周期，默认 100 ms。 */
  tickMs?: number
  /** 倍速递增间隔，默认 700 ms。 */
  multiplierStepMs?: number
  /** 进入长按后每个周期调用，参数为当前倍速。 */
  onStep?: (multiplier: number) => void
  /** 进入长按模式时调用一次。 */
  onLongPressStart?: () => void
  /** 长按结束（含取消）时调用一次。 */
  onLongPressEnd?: () => void
  /** 未达延迟即松手时调用一次。 */
  onTap?: () => void
  /** 自定义倍速计算；默认按 multiplierStepMs 递增并封顶 8×。 */
  getMultiplier?: (heldMs: number) => number
}

export const DEFAULT_LONG_PRESS_DELAY_MS = 350
const MAX_MULTIPLIER = 8

export function useLongPress(options: LongPressOptions = {}): {
  longPressing: import('vue').Ref<boolean>
  onPointerDown: (event?: PointerEvent) => void
  onPointerUp: () => void
  onPointerCancel: () => void
  onBlur: () => void
  dispose: () => void
} {
  const delayMs = options.delayMs ?? DEFAULT_LONG_PRESS_DELAY_MS
  const tickMs = options.tickMs ?? 100
  const multiplierStepMs = options.multiplierStepMs ?? 700

  const longPressing = ref(false)
  let pointerDownAt = 0
  let activePointerId: number | null = null
  let holdTimer: ReturnType<typeof setTimeout> | null = null
  let tickTimer: ReturnType<typeof setInterval> | null = null

  function clearHoldTimer(): void {
    if (holdTimer !== null) {
      clearTimeout(holdTimer)
      holdTimer = null
    }
  }

  function clearTickTimer(): void {
    if (tickTimer !== null) {
      clearInterval(tickTimer)
      tickTimer = null
    }
  }

  function defaultMultiplier(heldMs: number): number {
    const level = 1 + Math.floor(Math.max(0, heldMs - delayMs) / multiplierStepMs)
    return Math.min(MAX_MULTIPLIER, 2 ** (level - 1))
  }

  function endHold(): void {
    clearHoldTimer()
    clearTickTimer()
    activePointerId = null
    if (longPressing.value) {
      longPressing.value = false
      options.onLongPressEnd?.()
    }
  }

  function beginRepeat(): void {
    holdTimer = null
    longPressing.value = true
    options.onLongPressStart?.()
    const fire = (): void => {
      const heldMs = Date.now() - pointerDownAt
      const multiplier = options.getMultiplier ? options.getMultiplier(heldMs) : defaultMultiplier(heldMs)
      options.onStep?.(multiplier)
    }
    fire()
    tickTimer = setInterval(fire, tickMs)
  }

  function detachWindowListeners(): void {
    if (typeof window === 'undefined') return
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointercancel', onPointerCancel)
  }

  function onPointerDown(event?: PointerEvent): void {
    if (event && activePointerId !== null) return
    if (event) activePointerId = event.pointerId
    pointerDownAt = Date.now()
    holdTimer = setTimeout(beginRepeat, delayMs)
    if (typeof window !== 'undefined') {
      window.addEventListener('pointerup', onPointerUp)
      window.addEventListener('pointercancel', onPointerCancel)
    }
  }

  function onPointerUp(): void {
    detachWindowListeners()
    if (longPressing.value) {
      endHold()
      return
    }
    clearHoldTimer()
    activePointerId = null
    options.onTap?.()
  }

  function onPointerCancel(): void {
    detachWindowListeners()
    endHold()
  }

  function onBlur(): void {
    detachWindowListeners()
    endHold()
  }

  function onVisibilityChange(): void {
    if (typeof document !== 'undefined' && document.hidden) {
      detachWindowListeners()
      endHold()
    }
  }

  if (typeof window !== 'undefined') {
    window.addEventListener('blur', onBlur)
  }
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onVisibilityChange)
  }

  function dispose(): void {
    detachWindowListeners()
    if (typeof window !== 'undefined') window.removeEventListener('blur', onBlur)
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisibilityChange)
    endHold()
  }

  return { longPressing, onPointerDown, onPointerUp, onPointerCancel, onBlur, dispose }
}
