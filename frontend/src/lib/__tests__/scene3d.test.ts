import { describe, expect, it } from 'vitest'
import type { SimulationState } from '../../contracts'
import { BodyTrajectoryBuffer } from '../trajectoryBuffer'
import {
  BASE_BODY_RADIUS,
  FREE_CAMERA_MAX_DISTANCE_MULTIPLIER,
  MAX_3D_TRAIL_POINTS,
  cameraPresetPose,
  createSceneTransform,
  createTrajectoryRenderCache,
  effective3dDevicePixelRatio,
  orthographicCameraFrame,
  perspectiveCameraFrame,
  perspectiveClipping,
  syncTrajectoryRenderCache,
  transformScenePosition,
  visualBodyRadius,
} from '../scene3d'

const spatialState: SimulationState = {
  step: 10,
  simulationTimeSeconds: 5,
  bodies: [
    { id: 'a', position: { x: -10, y: 20, z: -30 }, velocity: { x: 1, y: 2, z: 3 } },
    { id: 'b', position: { x: 30, y: -20, z: 10 }, velocity: { x: -1, y: -2, z: -3 } },
  ],
}

describe('scene3d transform', () => {
  it('以 AABB 中心冻结 SI 到场景坐标的变换且不修改输入', () => {
    const before = structuredClone(spatialState)
    const transform = createSceneTransform(spatialState)
    expect(transform).not.toBeNull()
    expect(transform?.origin).toEqual({ x: 10, y: 0, z: -10 })
    expect(transform?.metersPerUnit).toBeCloseTo(40 / 24)
    expect(transformScenePosition(spatialState.bodies[0].position, transform!)).toEqual({ x: -12, y: 12, z: -12 })
    expect(spatialState).toEqual(before)
  })

  it('初始退化状态不冻结变换', () => {
    const degenerate = structuredClone(spatialState)
    degenerate.bodies[1].position = { ...degenerate.bodies[0].position }
    expect(createSceneTransform(degenerate)).toBeNull()
  })

  it('统一视觉半径、限制 DPR 并提供指定相机轴向', () => {
    expect(visualBodyRadius()).toBe(BASE_BODY_RADIUS)
    expect(BASE_BODY_RADIUS).toBe(0.18)
    expect(effective3dDevicePixelRatio(0.5)).toBe(1)
    expect(effective3dDevicePixelRatio(3)).toBe(2)
    expect(cameraPresetPose('XY')).toEqual({ direction: { x: 0, y: 0, z: 1 }, up: { x: 0, y: 1, z: 0 } })
    expect(cameraPresetPose('XZ').direction).toEqual({ x: 0, y: -1, z: 0 })
    expect(cameraPresetPose('YZ').direction).toEqual({ x: 1, y: 0, z: 0 })
  })

  it('横纵屏均完整适配透视视锥并把最远距离限制为内容尺度 100 倍', () => {
    const landscape = perspectiveCameraFrame(12, 16 / 9, 0.8)
    const portrait = perspectiveCameraFrame(12, 9 / 16, 0.8)
    expect(landscape.maxDistance).toBe(12 * FREE_CAMERA_MAX_DISTANCE_MULTIPLIER)
    expect(landscape.far).toBeGreaterThan(landscape.maxDistance + 12)
    expect(landscape.minDistance).toBeGreaterThanOrEqual(2)
    expect(portrait.distance).toBeGreaterThan(landscape.distance)

    const farthest = perspectiveClipping(landscape.maxDistance, 12)
    expect(farthest.far).toBeGreaterThan(landscape.maxDistance + 12)
    expect(farthest.near).toBeGreaterThan(0)
  })

  it('正交视图按宽高比适配且不产生透视距离缩短', () => {
    const landscape = orthographicCameraFrame(10, 2)
    const portrait = orthographicCameraFrame(10, 0.5)
    expect(landscape.halfHeight).toBeCloseTo(12.5)
    expect(portrait.halfHeight).toBeCloseTo(25)
    expect(landscape.minZoom).toBe(0.01)
    expect(landscape.maxZoom).toBe(50)
    expect(landscape.far).toBeGreaterThan(10 * FREE_CAMERA_MAX_DISTANCE_MULTIPLIER)
  })
})

describe('3D trajectory cache', () => {
  it('支持 cutoff 前进增量写入、回退重建和 ring 覆盖重建', () => {
    const transform = createSceneTransform(spatialState)!
    const source = new BodyTrajectoryBuffer(3)
    source.append(1, -10, 20, -30)
    source.append(2, 0, 10, -20)
    source.append(3, 10, 0, -10)
    const cache = createTrajectoryRenderCache(source.capacity)

    expect(syncTrajectoryRenderCache(source, transform, 2, cache)).toEqual({ rebuilt: true, pointCount: 2 })
    expect(syncTrajectoryRenderCache(source, transform, 3, cache)).toEqual({ rebuilt: false, pointCount: 3 })
    expect(Array.from(cache.positions.slice(6, 9))).toEqual([0, 0, 0])

    expect(syncTrajectoryRenderCache(source, transform, 2, cache)).toEqual({ rebuilt: true, pointCount: 2 })
    source.append(4, 20, -10, 0)
    expect(syncTrajectoryRenderCache(source, transform, 4, cache)).toEqual({ rebuilt: true, pointCount: 3 })
    expect(cache.sourceEarliestStep).toBe(2)
    expect(cache.visibleLatestStep).toBe(4)
  })

  it('无论源容量多大都只预分配现有 8,000 点上限', () => {
    expect(createTrajectoryRenderCache(20_000).positions.length).toBe(MAX_3D_TRAIL_POINTS * 3)
  })
})
