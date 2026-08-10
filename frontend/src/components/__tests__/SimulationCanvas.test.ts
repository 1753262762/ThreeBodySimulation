import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { SimulationState } from '../../contracts'
import SimulationCanvas from '../SimulationCanvas.vue'

const context = {
  arc: vi.fn(),
  beginPath: vi.fn(),
  clearRect: vi.fn(),
  createRadialGradient: vi.fn(() => ({ addColorStop: vi.fn() })),
  fill: vi.fn(),
  fillRect: vi.fn(),
  fillText: vi.fn(),
  lineTo: vi.fn(),
  moveTo: vi.fn(),
  stroke: vi.fn(),
  fillStyle: '',
  font: '',
  globalAlpha: 1,
  lineWidth: 1,
  strokeStyle: '',
  textBaseline: '',
} as unknown as CanvasRenderingContext2D

const state: SimulationState = {
  step: 1,
  simulationTimeSeconds: 60,
  bodies: [
    { id: 'a', position: { x: 1, y: 2, z: 3 }, velocity: { x: 0, y: 0, z: 0 } },
    { id: 'b', position: { x: -1, y: -2, z: -3 }, velocity: { x: 0, y: 0, z: 0 } },
  ],
}

describe('SimulationCanvas', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      disconnect() {}
    })
    vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
    vi.stubGlobal('cancelAnimationFrame', vi.fn())
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context)
  })

  it.each(['XY', 'XZ', 'YZ'] as const)('渲染 %s 投影并提供可访问标签', (projection) => {
    const wrapper = mount(SimulationCanvas, {
      props: {
        state,
        trailsPerBody: new Map([['a', [1, 2, 3, 2, 3, 4]]]),
        trailVersion: 1,
        projection,
        showTrails: true,
        showLabels: true,
        showGrid: true,
        bodyNames: new Map([['a', '天体 A']]),
        bodyColors: new Map([['a', '#ffffff']]),
        nearestPairIds: ['a', 'b'],
      },
    })

    expect(wrapper.get('canvas').attributes('aria-label')).toContain(`${projection} 投影视图`)
    expect(context.arc).toHaveBeenCalled()
    wrapper.unmount()
  })
})
