/** Offline-only Health test double. Production UI never imports rules from this module. */
import type { Metrics, SimulationConfig, SimulationEvent, SimulationHealthReport } from '../contracts'

const WARNING = 0.001
const POOR = 0.01

export function updateMockHealth(
  config: SimulationConfig,
  initial: Metrics,
  current: Metrics,
  previous: SimulationHealthReport | null,
  step: number,
  simulationTimeSeconds: number,
): SimulationHealthReport {
  const energy = Math.abs(current.relativeEnergyDrift)
  const angularReference = initial.angularMomentumMagnitude
  const angular = angularReference > 0
    ? Math.hypot(
      current.angularMomentum.x - initial.angularMomentum.x,
      current.angularMomentum.y - initial.angularMomentum.y,
      current.angularMomentum.z - initial.angularMomentum.z,
    ) / angularReference
    : null
  const peakEnergy = Math.max(previous?.metrics.peakEnergyDrift ?? 0, energy)
  const peakAngular = angular == null ? previous?.metrics.peakAngularMomentumDrift ?? null
    : Math.max(previous?.metrics.peakAngularMomentumDrift ?? 0, angular)
  const peak = Math.max(peakEnergy, peakAngular ?? 0)
  const status = peak >= POOR ? 'POOR' : peak >= WARNING ? 'WARNING' : 'GOOD'
  const recommendation = status === 'GOOD' ? [] : [{
    code: 'REDUCE_TIME_STEP' as const,
    action: 'CLONE_AND_RETRY' as const,
    message: 'Suggested Experiment: clone this run with a smaller integration time step.',
    configPatch: { timeStepSeconds: config.timeStepSeconds / 10 },
  }]
  return {
    status,
    metrics: {
      currentEnergyDrift: energy,
      peakEnergyDrift: peakEnergy,
      peakEnergyDriftStep: peakEnergy > (previous?.metrics.peakEnergyDrift ?? -1)
        ? step : previous?.metrics.peakEnergyDriftStep ?? step,
      peakEnergyDriftSimulationTimeSeconds: peakEnergy > (previous?.metrics.peakEnergyDrift ?? -1)
        ? simulationTimeSeconds : previous?.metrics.peakEnergyDriftSimulationTimeSeconds ?? simulationTimeSeconds,
      currentAngularMomentumDrift: angular,
      peakAngularMomentumDrift: peakAngular,
      peakAngularMomentumDriftStep: angular != null && angular >= (previous?.metrics.peakAngularMomentumDrift ?? -1)
        ? step : previous?.metrics.peakAngularMomentumDriftStep ?? null,
      peakAngularMomentumDriftSimulationTimeSeconds: angular != null && angular >= (previous?.metrics.peakAngularMomentumDrift ?? -1)
        ? simulationTimeSeconds : previous?.metrics.peakAngularMomentumDriftSimulationTimeSeconds ?? null,
      energyTrend: 'STABLE',
      angularMomentumTrend: 'STABLE',
      closestApproachMeters: current.allTimeMinimumPairDistanceMeters ?? current.minimumPairDistanceMeters,
      closeEncounterCount: previous?.metrics.closeEncounterCount ?? 0,
      latestCloseEncounter: previous?.metrics.latestCloseEncounter ?? null,
    },
    thresholds: {
      energyWarning: WARNING,
      energyPoor: POOR,
      angularMomentumWarning: WARNING,
      angularMomentumPoor: POOR,
    },
    reasons: status === 'GOOD' ? [{
      code: 'CONSERVATION_WITHIN_TOLERANCE', severity: 'INFO', metric: null,
      message: 'Sampled conservation drift remains within the project tolerance.',
    }] : [{
      code: peakEnergy >= WARNING ? (peakEnergy >= POOR ? 'ENERGY_DRIFT_HIGH' : 'ENERGY_DRIFT_ELEVATED')
        : (peak >= POOR ? 'ANGULAR_MOMENTUM_DRIFT_HIGH' : 'ANGULAR_MOMENTUM_DRIFT_ELEVATED'),
      severity: status === 'POOR' ? 'ERROR' : 'WARNING', metric: 'conservation',
      message: 'Sampled conservation drift exceeded the project tolerance.',
    }],
    recommendations: recommendation,
    failure: null,
    analyzedStep: step,
    analyzedSimulationTimeSeconds: simulationTimeSeconds,
    sampleStride: 1,
    sampleCount: (previous?.sampleCount ?? 0) + 1,
  }
}

export function failMockHealth(
  report: SimulationHealthReport | null,
  step: number,
  simulationTimeSeconds: number,
  message: string,
): SimulationHealthReport | null {
  if (!report) return null
  return {
    ...report,
    status: 'FAILED',
    reasons: [...report.reasons, { code: 'NON_FINITE_STATE', severity: 'ERROR', metric: 'state', message }],
    failure: { code: 'NON_FINITE_STATE', bodyId: null, field: null, step, simulationTimeSeconds, value: null, message },
  }
}

export function observeMockEncounter(
  report: SimulationHealthReport | null,
  event: SimulationEvent,
): SimulationHealthReport | null {
  if (!report) return null
  const distance = event.closestDistanceMeters ?? event.distanceMeters ?? null
  return {
    ...report,
    metrics: {
      ...report.metrics,
      closestApproachMeters: distance == null ? report.metrics.closestApproachMeters
        : Math.min(report.metrics.closestApproachMeters ?? distance, distance),
      closeEncounterCount: report.metrics.closeEncounterCount + (event.phase === 'ENTER' ? 1 : 0),
      latestCloseEncounter: distance == null || !event.eventId ? report.metrics.latestCloseEncounter : {
        eventId: event.eventId,
        bodyIds: event.bodyIds ?? [],
        distanceMeters: distance,
        step: event.closestStep ?? event.step,
        simulationTimeSeconds: event.closestSimulationTimeSeconds ?? event.simulationTimeSeconds,
      },
    },
  }
}
