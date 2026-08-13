import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import SimulationScene3D from '../SimulationScene3D.vue'
import { TrajectoryBuffer } from '../../lib/trajectoryBuffer'

describe('SimulationScene3D', () => {
  it('WebGL2 初始化失败时显示内联降级提示', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const wrapper = mount(SimulationScene3D, {
      props: {
        experimentKey: 'experiment-1',
        state: null,
        trajectories: new TrajectoryBuffer(),
        trailVersion: 0,
        showTrails: true,
        showGrid: true,
        bodyColors: new Map(),
      },
    })

    expect(wrapper.get('[role="alert"]').text()).toContain('无法初始化 WebGL2')
    expect(wrapper.find('[data-testid="simulation-scene-3d-canvas"]').exists()).toBe(false)
    wrapper.unmount()
    warn.mockRestore()
  })
})
