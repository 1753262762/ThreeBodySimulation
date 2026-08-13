import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Metrics, SimulationHealthReport } from '../../contracts'
import KpiCards from '../KpiCards.vue'

const metrics: Metrics = {
  kineticEnergyJoules: 1, potentialEnergyJoules: -101, totalEnergyJoules: -100,
  initialTotalEnergyJoules: -100, relativeEnergyDrift: 0.02,
  angularMomentum: { x: 0, y: 0, z: 2 }, angularMomentumMagnitude: 2,
  linearMomentum: { x: 0, y: 0, z: 0 }, linearMomentumMagnitude: 0,
  minimumPairDistanceMeters: 2,
}
const health: SimulationHealthReport = {
  status: 'WARNING',
  metrics: {
    currentEnergyDrift: 0.0002, peakEnergyDrift: 0.002,
    peakEnergyDriftStep: 8, peakEnergyDriftSimulationTimeSeconds: 800,
    currentAngularMomentumDrift: 0.0001, peakAngularMomentumDrift: 0.0015,
    peakAngularMomentumDriftStep: 7, peakAngularMomentumDriftSimulationTimeSeconds: 700,
    energyTrend: 'STABLE', angularMomentumTrend: 'SLOWLY_INCREASING',
    closestApproachMeters: 2, closeEncounterCount: 1, latestCloseEncounter: null,
  },
  thresholds: { energyWarning: 0.001, energyPoor: 0.01, angularMomentumWarning: 0.001, angularMomentumPoor: 0.01 },
  reasons: [{ code: 'ENERGY_DRIFT_ELEVATED', severity: 'WARNING', metric: 'energy', message: 'Peak drift crossed tolerance.' }],
  recommendations: [{ code: 'REDUCE_TIME_STEP', action: 'CLONE_AND_RETRY', message: 'Suggested Experiment', configPatch: { timeStepSeconds: 10 } }],
  failure: null, analyzedStep: 10, analyzedSimulationTimeSeconds: 1000, sampleStride: 1, sampleCount: 11,
}

describe('KpiCards', () => {
  it('renders authoritative Health without classifying instantaneous metric drift', async () => {
    const wrapper = mount(KpiCards, {
      props: { metrics, health },
    })
    expect(wrapper.get('.total-energy-kpi').classes()).not.toContain('health-warning')
    expect(wrapper.get('.health-card-summary').text()).toContain('需要谨慎解读')
    await wrapper.get('.health-card-summary').trigger('click')
    expect(wrapper.text()).toContain('0.2%')
    expect(wrapper.text()).toContain('能量漂移峰值超过注意阈值')
  })

  it('emits the backend recommendation time step for clone and retry', async () => {
    const wrapper = mount(KpiCards, {
      props: { metrics, health },
    })
    await wrapper.get('.health-card-summary').trigger('click')
    await wrapper.get('.health-clone-button').trigger('click')
    expect(wrapper.emitted('cloneRetry')).toEqual([[10]])
  })
})
