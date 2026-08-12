import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useLongPress } from '../useLongPress'

describe('useLongPress', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('未达延迟即松手视为单击', () => {
    const onTap = vi.fn()
    const onLongPressStart = vi.fn()
    const { onPointerDown, onPointerUp, dispose } = useLongPress({ onTap, onLongPressStart, delayMs: 350 })
    onPointerDown()
    vi.advanceTimersByTime(200)
    onPointerUp()
    expect(onTap).toHaveBeenCalledTimes(1)
    expect(onLongPressStart).not.toHaveBeenCalled()
    dispose()
  })

  it('达到延迟后进入长按并开始触发', () => {
    const onStep = vi.fn()
    const onLongPressStart = vi.fn()
    const { longPressing, onPointerDown, onPointerUp, dispose } = useLongPress({ onStep, onLongPressStart, delayMs: 350 })
    onPointerDown()
    vi.advanceTimersByTime(350)
    expect(longPressing.value).toBe(true)
    expect(onLongPressStart).toHaveBeenCalledTimes(1)
    expect(onStep).toHaveBeenCalled()
    expect(onStep.mock.calls[0]?.[0]).toBe(1)
    onPointerUp()
    dispose()
  })

  it('按住时间增加后倍速递增并封顶 8×', () => {
    const steps: number[] = []
    const { onPointerDown, onPointerUp, dispose } = useLongPress({
      delayMs: 350,
      tickMs: 100,
      multiplierStepMs: 700,
      onStep: (multiplier) => steps.push(multiplier),
    })
    onPointerDown()
    vi.advanceTimersByTime(350) // 进入长按，1×
    vi.advanceTimersByTime(700) // 2×
    vi.advanceTimersByTime(700) // 4×
    vi.advanceTimersByTime(700) // 8×
    vi.advanceTimersByTime(2000) // 继续按住仍封顶 8×
    onPointerUp()
    expect(Math.max(...steps)).toBe(8)
    expect(steps.some((m) => m === 2)).toBe(true)
    expect(steps.some((m) => m === 4)).toBe(true)
    expect(steps.every((m) => m <= 8)).toBe(true)
    dispose()
  })

  it('pointerup 结束长按并清理重复触发', () => {
    const onStep = vi.fn()
    const onLongPressEnd = vi.fn()
    const { onPointerDown, onPointerUp, dispose } = useLongPress({ onStep, onLongPressEnd, delayMs: 350 })
    onPointerDown()
    vi.advanceTimersByTime(350)
    const before = onStep.mock.calls.length
    expect(before).toBeGreaterThan(0)
    onPointerUp()
    expect(onLongPressEnd).toHaveBeenCalledTimes(1)
    const after = onStep.mock.calls.length
    vi.advanceTimersByTime(5000)
    expect(onStep.mock.calls.length).toBe(after)
    dispose()
  })

  it('pointercancel 结束长按但不触发单击', () => {
    const onTap = vi.fn()
    const onLongPressEnd = vi.fn()
    const { onPointerDown, onPointerCancel, dispose } = useLongPress({ onTap, onLongPressEnd, delayMs: 350 })
    onPointerDown()
    vi.advanceTimersByTime(500)
    onPointerCancel()
    expect(onLongPressEnd).toHaveBeenCalledTimes(1)
    expect(onTap).not.toHaveBeenCalled()
    dispose()
  })

  it('窗口 blur 清理长按定时器', () => {
    const onStep = vi.fn()
    const { onPointerDown, dispose } = useLongPress({ onStep, delayMs: 350 })
    onPointerDown()
    vi.advanceTimersByTime(400)
    const before = onStep.mock.calls.length
    window.dispatchEvent(new Event('blur'))
    vi.advanceTimersByTime(5000)
    expect(onStep.mock.calls.length).toBe(before)
    dispose()
  })

  it('document 隐藏时清理长按定时器', () => {
    const onStep = vi.fn()
    const { onPointerDown, dispose } = useLongPress({ onStep, delayMs: 350 })
    onPointerDown()
    vi.advanceTimersByTime(400)
    const before = onStep.mock.calls.length
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(5000)
    expect(onStep.mock.calls.length).toBe(before)
    delete (document as unknown as { hidden?: boolean }).hidden
    dispose()
  })

  it('dispose 清理所有监听与定时器', () => {
    const onTap = vi.fn()
    const onStep = vi.fn()
    const handle = useLongPress({ onTap, onStep, delayMs: 350 })
    handle.onPointerDown()
    vi.advanceTimersByTime(400)
    const stepsBeforeDispose = onStep.mock.calls.length
    handle.dispose()
    vi.advanceTimersByTime(5000)
    // dispose 后 window 上的监听已移除，真实 pointerup 不再触发单击。
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }))
    expect(onTap).not.toHaveBeenCalled()
    expect(onStep.mock.calls.length).toBe(stepsBeforeDispose)
    expect(handle.longPressing.value).toBe(false)
  })
})
