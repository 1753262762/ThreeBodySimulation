import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Metrics, SimulationConfig } from '../../contracts'
import KpiCards from '../KpiCards.vue'

const config: SimulationConfig = {
  name: 'KPI 测试',
  timeStepSeconds: 1,
  gravitationalConstant: 1,
  softeningLengthMeters: 0.1,
  maxSteps: 100,
  targetSimulationTimeSeconds: null,
  bodies: [
    {
      id: 'a', name: 'A', color: '#ffffff', massKg: 2,
      position: { x: 1, y: 0, z: 0 }, velocity: { x: 0, y: 1, z: 0 },
    },
  ],
}

function metrics(angularX: number, energyDrift = 0): Metrics {
  return {
    kineticEnergyJoules: 0,
    potentialEnergyJoules: 0,
    totalEnergyJoules: -100 + 100 * energyDrift,
    initialTotalEnergyJoules: -100,
    relativeEnergyDrift: energyDrift,
    angularMomentum: { x: angularX, y: 0, z: 2 },
    angularMomentumMagnitude: Math.hypot(angularX, 2),
    linearMomentum: { x: 0, y: 0, z: 0 },
    linearMomentumMagnitude: 0,
    minimumPairDistanceMeters: 0,
  }
}

describe('KpiCards', () => {
  it('总能量与角动量使用相同的初始值和漂移状态结构', () => {
    const wrapper = mount(KpiCards, {
      props: { config, metrics: metrics(0, -0.02), step: 10, simulationTimeSeconds: 10 },
    })
    const energy = wrapper.get('.total-energy-kpi')
    expect(energy.text()).toContain('初始 -100')
    expect(energy.get('em').text()).toBe('-2% 漂移 · 警告')
    expect(energy.classes()).toContain('health-warning')
  })

  it('展示初始角动量、实时漂移和告警等级', async () => {
    const wrapper = mount(KpiCards, {
      props: { config, metrics: metrics(0.04), step: 10, simulationTimeSeconds: 10 },
    })
    const angular = wrapper.get('.angular-momentum-kpi')
    expect(angular.text()).toContain('初始 2')
    expect(angular.text()).toContain('+2% 漂移')
    expect(angular.get('em').text()).toBe('+2% 漂移 · 警告')
    expect(angular.classes()).toContain('health-warning')

    await wrapper.setProps({ metrics: metrics(0) })
    expect(angular.get('em').text()).toBe('0% 漂移 · 稳定')
    expect(angular.classes()).toContain('health-stable')
  })
})
