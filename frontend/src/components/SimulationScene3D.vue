<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import type { SimulationState } from '../contracts'
import type { CanvasPalette } from '../lib/theme'
import type { SnapshotBuffer } from '../lib/snapshotBuffer'
import { TrajectoryBuffer } from '../lib/trajectoryBuffer'
import {
  BODY_ATMOSPHERE_SCALE,
  cameraPresetPose,
  createSceneTransform,
  createTrajectoryRenderCache,
  effective3dDevicePixelRatio,
  orthographicCameraFrame,
  perspectiveCameraFrame,
  perspectiveClipping,
  sceneTransformKey,
  syncTrajectoryRenderCache,
  transformScenePosition,
  visualBodyRadius,
  type SceneCameraPreset,
  type SceneTransform,
  type TrajectoryRenderCache,
} from '../lib/scene3d'

const props = withDefaults(defineProps<{
  experimentKey: string | null
  state: SimulationState | null
  snapshotBuffer?: SnapshotBuffer | null
  trajectories: TrajectoryBuffer
  trailVersion: number
  trailCutoffStep?: number
  showTrails: boolean
  showGrid: boolean
  bodyColors: Map<string, string>
  palette?: CanvasPalette
}>(), {
  snapshotBuffer: null,
  trailCutoffStep: Number.POSITIVE_INFINITY,
  palette: undefined,
})

type SceneCamera = THREE.PerspectiveCamera | THREE.OrthographicCamera

interface BodyVisual {
  group: THREE.Group
  surface: THREE.Mesh<THREE.SphereGeometry, THREE.MeshStandardMaterial>
  atmosphere: THREE.Mesh<THREE.SphereGeometry, THREE.ShaderMaterial>
  appearanceKey: string
  radius: number
}

interface TrailVisual {
  line: THREE.Line<THREE.BufferGeometry, THREE.LineBasicMaterial>
  attribute: THREE.BufferAttribute
  cache: TrajectoryRenderCache
}

const hostRef = ref<HTMLDivElement | null>(null)
const initializationError = ref<string | null>(null)

let scene: THREE.Scene | null = null
let perspectiveCamera: THREE.PerspectiveCamera | null = null
let orthographicCamera: THREE.OrthographicCamera | null = null
let activeCamera: SceneCamera | null = null
let renderer: THREE.WebGLRenderer | null = null
let controls: OrbitControls | null = null
let grid: THREE.GridHelper | null = null
let sharedBodyGeometry: THREE.SphereGeometry | null = null
let resizeObserver: ResizeObserver | null = null
let rafId: number | null = null
let frozenTransform: SceneTransform | null = null
let lastTrailSignature = ''
let cameraNeedsReset = true
let currentPreset: SceneCameraPreset = 'FREE'
let viewportAspect = 1

const bodyVisuals = new Map<string, BodyVisual>()
const trailVisuals = new Map<string, TrailVisual>()
const contentBox = new THREE.Box3()
const scratchPoint = new THREE.Vector3()

function palette(): CanvasPalette {
  return props.palette ?? {
    background: '#05080f',
    gridLine: 'rgba(111, 137, 168, 0.22)',
    axisLine: 'rgba(111, 137, 168, 0.5)',
    hudText: 'rgba(141, 149, 162, 0.85)',
  }
}

function displayState(nowMs: number): SimulationState | null {
  return props.snapshotBuffer?.readInterpolated(nowMs) ?? props.state
}

function disposeMaterial(material: THREE.Material | THREE.Material[]): void {
  if (Array.isArray(material)) material.forEach((item) => item.dispose())
  else material.dispose()
}

function createAtmosphereMaterial(color: string): THREE.ShaderMaterial {
  return new THREE.ShaderMaterial({
    uniforms: {
      glowColor: { value: new THREE.Color(color) },
      intensity: { value: 0.34 },
    },
    vertexShader: `
      varying vec3 vNormal;
      varying vec3 vViewDirection;
      void main() {
        vec4 viewPosition = modelViewMatrix * vec4(position, 1.0);
        vNormal = normalize(normalMatrix * normal);
        vViewDirection = normalize(-viewPosition.xyz);
        gl_Position = projectionMatrix * viewPosition;
      }
    `,
    fragmentShader: `
      uniform vec3 glowColor;
      uniform float intensity;
      varying vec3 vNormal;
      varying vec3 vViewDirection;
      void main() {
        float fresnel = pow(1.0 - max(dot(vNormal, vViewDirection), 0.0), 2.4);
        gl_FragColor = vec4(glowColor, fresnel * intensity);
      }
    `,
    side: THREE.BackSide,
    transparent: true,
    depthWrite: false,
    blending: THREE.AdditiveBlending,
    toneMapped: false,
  })
}

function createBodyVisual(bodyId: string, color: string): BodyVisual | null {
  if (!scene || !sharedBodyGeometry) return null
  const material = new THREE.MeshStandardMaterial({
    color,
    roughness: 0.72,
    metalness: 0,
    emissive: new THREE.Color(color),
    emissiveIntensity: 0.1,
  })
  const surface = new THREE.Mesh(sharedBodyGeometry, material)
  surface.userData.bodyId = bodyId
  const atmosphere = new THREE.Mesh(sharedBodyGeometry, createAtmosphereMaterial(color))
  atmosphere.scale.setScalar(BODY_ATMOSPHERE_SCALE)
  atmosphere.renderOrder = 2
  const group = new THREE.Group()
  group.add(surface, atmosphere)
  scene.add(group)
  const visual = {
    group,
    surface,
    atmosphere,
    appearanceKey: color.toLowerCase(),
    radius: 1,
  }
  bodyVisuals.set(bodyId, visual)
  return visual
}

function refreshBodyAppearance(visual: BodyVisual, color: string): void {
  const nextKey = color.toLowerCase()
  if (visual.appearanceKey === nextKey) return
  visual.surface.material.color.set(color)
  visual.surface.material.emissive.set(color)
  visual.atmosphere.material.uniforms.glowColor.value.set(color)
  visual.appearanceKey = nextKey
}

function disposeBodyVisual(bodyId: string): void {
  const visual = bodyVisuals.get(bodyId)
  if (!visual) return
  scene?.remove(visual.group)
  visual.surface.material.dispose()
  visual.atmosphere.material.dispose()
  bodyVisuals.delete(bodyId)
}

function disposeTrailVisual(bodyId: string): void {
  const visual = trailVisuals.get(bodyId)
  if (!visual) return
  scene?.remove(visual.line)
  visual.line.geometry.dispose()
  visual.line.material.dispose()
  trailVisuals.delete(bodyId)
}

function disposeDynamicResources(): void {
  for (const bodyId of Array.from(bodyVisuals.keys())) disposeBodyVisual(bodyId)
  for (const bodyId of Array.from(trailVisuals.keys())) disposeTrailVisual(bodyId)
  contentBox.makeEmpty()
}

function expandContentForBody(visual: BodyVisual): void {
  const radius = visual.radius * BODY_ATMOSPHERE_SCALE
  const position = visual.group.position
  contentBox.expandByPoint(scratchPoint.set(position.x - radius, position.y - radius, position.z - radius))
  contentBox.expandByPoint(scratchPoint.set(position.x + radius, position.y + radius, position.z + radius))
}

function expandContentForTrail(cache: TrajectoryRenderCache, fromPoint = 0): void {
  for (let index = Math.max(0, fromPoint); index < cache.pointCount; index += 1) {
    const offset = index * 3
    contentBox.expandByPoint(scratchPoint.set(
      cache.positions[offset],
      cache.positions[offset + 1],
      cache.positions[offset + 2],
    ))
  }
}

function rebuildContentBounds(): void {
  contentBox.makeEmpty()
  for (const visual of bodyVisuals.values()) expandContentForBody(visual)
  for (const visual of trailVisuals.values()) expandContentForTrail(visual.cache)
}

function contentBounds(): { center: THREE.Vector3; radius: number; largestBodyRadius: number } {
  if (contentBox.isEmpty()) return { center: new THREE.Vector3(), radius: 12, largestBodyRadius: 1 }
  const sphere = contentBox.getBoundingSphere(new THREE.Sphere())
  let largestBodyRadius = 0
  for (const visual of bodyVisuals.values()) largestBodyRadius = Math.max(largestBodyRadius, visual.radius)
  return {
    center: sphere.center,
    radius: Math.max(1, sphere.radius),
    largestBodyRadius: Math.max(0.1, largestBodyRadius),
  }
}

function updateBodies(state: SimulationState): void {
  if (!scene) return
  if (!frozenTransform) {
    frozenTransform = createSceneTransform(state)
    if (frozenTransform) {
      lastTrailSignature = ''
      cameraNeedsReset = true
      contentBox.makeEmpty()
    }
  }
  const activeIds = new Set<string>()
  const degenerateOrigin = state.bodies.find((body) => (
    Number.isFinite(body.position.x) && Number.isFinite(body.position.y) && Number.isFinite(body.position.z)
  ))?.position

  const layouts = state.bodies.map((body) => ({
    body,
    position: frozenTransform
      ? transformScenePosition(body.position, frozenTransform)
      : {
          x: body.position.x - (degenerateOrigin?.x ?? body.position.x),
          y: body.position.y - (degenerateOrigin?.y ?? body.position.y),
          z: body.position.z - (degenerateOrigin?.z ?? body.position.z),
        },
  }))

  for (let index = 0; index < layouts.length; index += 1) {
    const { body, position } = layouts[index]
    activeIds.add(body.id)
    const color = props.bodyColors.get(body.id) ?? '#ffffff'
    const visual = bodyVisuals.get(body.id) ?? createBodyVisual(body.id, color)
    if (!visual) continue
    refreshBodyAppearance(visual, color)
    visual.group.position.set(position.x, position.y, position.z)
    visual.radius = visualBodyRadius()
    visual.group.scale.setScalar(visual.radius)
    expandContentForBody(visual)
  }

  let removed = false
  for (const bodyId of Array.from(bodyVisuals.keys())) {
    if (!activeIds.has(bodyId)) {
      disposeBodyVisual(bodyId)
      removed = true
    }
  }
  if (removed) rebuildContentBounds()
}

function createTrailVisual(bodyId: string, capacity: number): TrailVisual | null {
  if (!scene) return null
  const cache = createTrajectoryRenderCache(capacity)
  const geometry = new THREE.BufferGeometry()
  const attribute = new THREE.BufferAttribute(cache.positions, 3)
  attribute.setUsage(THREE.DynamicDrawUsage)
  geometry.setAttribute('position', attribute)
  geometry.setDrawRange(0, 0)
  const material = new THREE.LineBasicMaterial({
    color: props.bodyColors.get(bodyId) ?? '#ffffff',
    transparent: true,
    opacity: 0.72,
  })
  const line = new THREE.Line(geometry, material)
  line.frustumCulled = false
  scene.add(line)
  const visual = { line, attribute, cache }
  trailVisuals.set(bodyId, visual)
  return visual
}

function updateTrails(): void {
  if (!frozenTransform) return
  const activeIds = new Set<string>()
  let requiresBoundsRebuild = false
  const appended: Array<{ cache: TrajectoryRenderCache; fromPoint: number }> = []
  props.trajectories.forEachBody((bodyId, source) => {
    activeIds.add(bodyId)
    const visual = trailVisuals.get(bodyId) ?? createTrailVisual(bodyId, source.capacity)
    if (!visual) return
    const previousPointCount = visual.cache.pointCount
    const result = syncTrajectoryRenderCache(source, frozenTransform!, props.trailCutoffStep, visual.cache)
    visual.attribute.clearUpdateRanges()
    const updateStart = result.rebuilt ? 0 : previousPointCount
    if (result.pointCount > updateStart) {
      visual.attribute.addUpdateRange(updateStart * 3, (result.pointCount - updateStart) * 3)
    }
    visual.attribute.needsUpdate = true
    visual.line.geometry.setDrawRange(0, result.pointCount)
    visual.line.material.color.set(props.bodyColors.get(bodyId) ?? '#ffffff')
    visual.line.visible = props.showTrails
    if (result.rebuilt) requiresBoundsRebuild = true
    else appended.push({ cache: visual.cache, fromPoint: previousPointCount })
  })
  for (const bodyId of Array.from(trailVisuals.keys())) {
    if (!activeIds.has(bodyId)) {
      disposeTrailVisual(bodyId)
      requiresBoundsRebuild = true
    }
  }
  if (requiresBoundsRebuild) rebuildContentBounds()
  else appended.forEach(({ cache, fromPoint }) => expandContentForTrail(cache, fromPoint))
}

function configureControls(nextControls: OrbitControls): void {
  nextControls.enableDamping = true
  nextControls.dampingFactor = 0.08
  nextControls.screenSpacePanning = true
}

function activateCamera(nextCamera: SceneCamera): void {
  if (!renderer || activeCamera === nextCamera) return
  const previousTarget = controls?.target.clone() ?? new THREE.Vector3()
  controls?.dispose()
  activeCamera = nextCamera
  controls = new OrbitControls(nextCamera, renderer.domElement)
  configureControls(controls)
  controls.target.copy(previousTarget)
}

function configureOrthographicFrustum(camera: THREE.OrthographicCamera, halfHeight: number, aspect: number): void {
  camera.left = -halfHeight * aspect
  camera.right = halfHeight * aspect
  camera.top = halfHeight
  camera.bottom = -halfHeight
  camera.updateProjectionMatrix()
}

function setCameraPreset(preset: SceneCameraPreset): void {
  if (!perspectiveCamera || !orthographicCamera || !renderer) return
  const { center, radius, largestBodyRadius } = contentBounds()
  const pose = cameraPresetPose(preset)
  const direction = new THREE.Vector3(pose.direction.x, pose.direction.y, pose.direction.z).normalize()
  currentPreset = preset

  if (preset === 'FREE') {
    activateCamera(perspectiveCamera)
    const frame = perspectiveCameraFrame(radius, viewportAspect, largestBodyRadius, perspectiveCamera.fov)
    perspectiveCamera.aspect = viewportAspect
    perspectiveCamera.position.copy(center).addScaledVector(direction, frame.distance)
    perspectiveCamera.up.set(pose.up.x, pose.up.y, pose.up.z)
    perspectiveCamera.near = frame.near
    perspectiveCamera.far = frame.far
    perspectiveCamera.updateProjectionMatrix()
    if (controls) {
      controls.target.copy(center)
      controls.minDistance = frame.minDistance
      controls.maxDistance = frame.maxDistance
    }
  } else {
    activateCamera(orthographicCamera)
    const frame = orthographicCameraFrame(radius, viewportAspect)
    configureOrthographicFrustum(orthographicCamera, frame.halfHeight, viewportAspect)
    orthographicCamera.zoom = 1
    orthographicCamera.position.copy(center).addScaledVector(direction, frame.distance)
    orthographicCamera.up.set(pose.up.x, pose.up.y, pose.up.z)
    orthographicCamera.near = frame.near
    orthographicCamera.far = frame.far
    orthographicCamera.updateProjectionMatrix()
    if (controls) {
      controls.target.copy(center)
      controls.minZoom = frame.minZoom
      controls.maxZoom = frame.maxZoom
    }
  }
  activeCamera?.lookAt(center)
  controls?.update()
  cameraNeedsReset = false
  updateCameraDiagnostics()
}

function resetCamera(): void {
  setCameraPreset('FREE')
}

function updateCameraDiagnostics(): void {
  const canvas = renderer?.domElement
  if (!canvas || !activeCamera) return
  canvas.dataset.cameraType = activeCamera instanceof THREE.OrthographicCamera ? 'orthographic' : 'perspective'
  canvas.dataset.cameraPreset = currentPreset
  canvas.dataset.cameraNear = activeCamera.near.toPrecision(6)
  canvas.dataset.cameraFar = activeCamera.far.toPrecision(6)
  canvas.dataset.cameraMaxDistance = activeCamera instanceof THREE.PerspectiveCamera
    ? (controls?.maxDistance ?? 0).toPrecision(6)
    : 'orthographic'
  canvas.dataset.bodyVisualCount = bodyVisuals.size.toString()
  canvas.dataset.bodyGeometry = '64x48-shared'
}

function updateCameraConstraints(): void {
  if (!activeCamera || !controls) return
  const { center, radius, largestBodyRadius } = contentBounds()
  if (activeCamera instanceof THREE.PerspectiveCamera) {
    const frame = perspectiveCameraFrame(radius, viewportAspect, largestBodyRadius, activeCamera.fov)
    const clipping = perspectiveClipping(activeCamera.position.distanceTo(center), radius)
    activeCamera.near = clipping.near
    activeCamera.far = Math.max(frame.far, clipping.far)
    controls.minDistance = frame.minDistance
    controls.maxDistance = frame.maxDistance
    activeCamera.updateProjectionMatrix()
  } else {
    const frame = orthographicCameraFrame(radius, viewportAspect)
    activeCamera.near = frame.near
    activeCamera.far = frame.far
    controls.minZoom = frame.minZoom
    controls.maxZoom = frame.maxZoom
    activeCamera.updateProjectionMatrix()
  }
  updateCameraDiagnostics()
}

function resize(): void {
  const host = hostRef.value
  if (!host || !renderer || !perspectiveCamera || !orthographicCamera) return
  const rect = host.getBoundingClientRect()
  const width = Math.max(1, Math.floor(rect.width))
  const height = Math.max(1, Math.floor(rect.height))
  viewportAspect = width / height
  renderer.setPixelRatio(effective3dDevicePixelRatio(window.devicePixelRatio))
  renderer.setSize(width, height, false)
  perspectiveCamera.aspect = viewportAspect
  perspectiveCamera.updateProjectionMatrix()
  const frame = orthographicCameraFrame(contentBounds().radius, viewportAspect)
  configureOrthographicFrustum(orthographicCamera, frame.halfHeight, viewportAspect)
  updateCameraConstraints()
}

function renderFrame(nowMs: number): void {
  if (!scene || !activeCamera || !renderer || !controls) return
  const state = displayState(nowMs)
  if (state) updateBodies(state)
  else updateBodies({ step: 0, simulationTimeSeconds: 0, bodies: [] })

  const transformSignature = frozenTransform ? sceneTransformKey(frozenTransform) : 'pending'
  const trailSignature = [
    props.trailVersion,
    props.trailCutoffStep,
    props.showTrails,
    transformSignature,
  ].join(':')
  if (trailSignature !== lastTrailSignature) {
    updateTrails()
    lastTrailSignature = trailSignature
  }
  for (const visual of trailVisuals.values()) visual.line.visible = props.showTrails
  if (cameraNeedsReset && frozenTransform) resetCamera()
  controls.update()
  updateCameraConstraints()
  renderer.render(scene, activeCamera)
  rafId = requestAnimationFrame(renderFrame)
}

function initialize(): void {
  const host = hostRef.value
  if (!host) return
  initializationError.value = null
  try {
    scene = new THREE.Scene()
    scene.background = new THREE.Color(palette().background)
    perspectiveCamera = new THREE.PerspectiveCamera(45, 1, 0.001, 10_000)
    orthographicCamera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.001, 10_000)
    renderer = new THREE.WebGLRenderer({ antialias: true })
    renderer.outputColorSpace = THREE.SRGBColorSpace
    renderer.domElement.className = 'simulation-scene-3d-canvas'
    renderer.domElement.setAttribute('aria-label', '三维模拟视图')
    renderer.domElement.dataset.testid = 'simulation-scene-3d-canvas'
    host.appendChild(renderer.domElement)
    sharedBodyGeometry = new THREE.SphereGeometry(1, 64, 48)

    activeCamera = perspectiveCamera
    controls = new OrbitControls(activeCamera, renderer.domElement)
    configureControls(controls)

    scene.add(new THREE.HemisphereLight(0xbfdcff, 0x15182a, 1.35))
    const keyLight = new THREE.DirectionalLight(0xffffff, 2.25)
    keyLight.position.set(8, -10, 12)
    scene.add(keyLight)
    const fillLight = new THREE.DirectionalLight(0x7da7ff, 0.7)
    fillLight.position.set(-10, 6, -5)
    scene.add(fillLight)
    grid = new THREE.GridHelper(48, 24, palette().axisLine, palette().gridLine)
    grid.rotation.x = Math.PI / 2
    grid.visible = props.showGrid
    scene.add(grid)

    resize()
    resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(host)
    window.addEventListener('resize', resize)
    rafId = requestAnimationFrame(renderFrame)
  } catch (error) {
    initializationError.value = '无法初始化 WebGL2 三维视图，请切换回 2D 或检查浏览器图形加速。'
    renderer?.dispose()
    sharedBodyGeometry?.dispose()
    renderer = null
    sharedBodyGeometry = null
    scene = null
    perspectiveCamera = null
    orthographicCamera = null
    activeCamera = null
    controls = null
    if (error instanceof Error) console.warn('3D renderer initialization failed:', error.message)
  }
}

onMounted(initialize)

watch(() => props.experimentKey, () => {
  disposeDynamicResources()
  frozenTransform = null
  lastTrailSignature = ''
  cameraNeedsReset = true
  currentPreset = 'FREE'
})
watch(() => props.showGrid, (visible) => {
  if (grid) grid.visible = visible
})
watch(() => props.palette, () => {
  if (scene) scene.background = new THREE.Color(palette().background)
})

onBeforeUnmount(() => {
  if (rafId !== null) cancelAnimationFrame(rafId)
  resizeObserver?.disconnect()
  window.removeEventListener('resize', resize)
  controls?.dispose()
  disposeDynamicResources()
  sharedBodyGeometry?.dispose()
  if (grid) {
    grid.geometry.dispose()
    disposeMaterial(grid.material)
  }
  renderer?.dispose()
  scene?.clear()
})

defineExpose({ resetCamera, setCameraPreset })
</script>

<template>
  <div ref="hostRef" class="simulation-scene-3d" data-testid="simulation-scene-3d">
    <div v-if="initializationError" class="scene-3d-error" role="alert">
      {{ initializationError }}
    </div>
    <div v-else class="scene-3d-hint">左键旋转 · 滚轮缩放 · 右键平移</div>
  </div>
</template>
