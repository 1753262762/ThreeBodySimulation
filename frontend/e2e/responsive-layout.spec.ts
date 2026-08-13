import { expect, test } from '@playwright/test'

test.describe('桌面响应式布局', () => {
  test.use({ viewport: { width: 1920, height: 1080 } })

  test('1920×1080 首屏充分展示 Canvas 与四卡单行', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('.simulation-canvas-wrap')

    const canvasBox = await page.locator('.simulation-canvas-wrap').boundingBox()
    expect(canvasBox).not.toBeNull()
    expect(canvasBox!.height).toBeGreaterThan(350)

    const cards = page.locator('.chart-grid > .chart-card')
    await expect(cards).toHaveCount(4)
    await expect(cards.locator('header b')).toHaveText(['系统能量', '角动量', '天体间距', '实验控制'])
    const boxes = await cards.evaluateAll((elements) => elements.map((element) => {
      const rect = element.getBoundingClientRect()
      return { top: rect.top, bottom: rect.bottom }
    }))
    expect(new Set(boxes.map((box) => Math.round(box.top))).size).toBe(1)
    expect(Math.max(...boxes.map((box) => box.bottom))).toBeLessThanOrEqual(1080)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(1920)
  })

  test('连续滚轮缩放与重复适应后 Canvas 保持可见', async ({ page }) => {
    await page.goto('/')
    const canvas = page.locator('.simulation-canvas-dynamic')
    await canvas.hover()
    await page.mouse.wheel(0, -100)
    await page.mouse.wheel(0, -100)
    await page.getByRole('button', { name: '适应窗口', exact: true }).click()
    await page.getByRole('button', { name: '适应窗口', exact: true }).click()

    const dimensions = await canvas.evaluate((element) => {
      const target = element as HTMLCanvasElement
      const rect = target.getBoundingClientRect()
      return { width: target.width, height: target.height, cssWidth: rect.width, cssHeight: rect.height }
    })
    expect(dimensions.width).toBeGreaterThan(0)
    expect(dimensions.height).toBeGreaterThan(0)
    expect(dimensions.cssWidth).toBeGreaterThan(0)
    expect(dimensions.cssHeight).toBeGreaterThan(350)
  })

  test('3D 视图在放大和窗口 resize 后保持有效尺寸且无横向溢出', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: '3D', exact: true }).click()
    const canvas = page.getByTestId('simulation-scene-3d-canvas')
    await expect(canvas).toBeVisible()
    await expect(canvas).toHaveAttribute('data-camera-type', 'perspective')
    await page.getByRole('button', { name: 'XY', exact: true }).last().click()
    await expect(canvas).toHaveAttribute('data-camera-type', 'orthographic')
    await expect(canvas).toHaveAttribute('data-camera-preset', 'XY')
    await page.getByRole('button', { name: 'YZ', exact: true }).last().click()
    await expect(canvas).toHaveAttribute('data-camera-type', 'orthographic')
    await expect(canvas).toHaveAttribute('data-camera-preset', 'YZ')
    await page.getByRole('button', { name: 'Free', exact: true }).click()
    await expect(canvas).toHaveAttribute('data-camera-type', 'perspective')
    await canvas.hover()
    for (let index = 0; index < 30; index += 1) await page.mouse.wheel(0, 1000)
    const cameraRange = await canvas.evaluate((element) => ({
      near: Number((element as HTMLElement).dataset.cameraNear),
      far: Number((element as HTMLElement).dataset.cameraFar),
      maxDistance: Number((element as HTMLElement).dataset.cameraMaxDistance),
      contextLost: (element as HTMLCanvasElement).getContext('webgl2')?.isContextLost() ?? true,
    }))
    expect(cameraRange.near).toBeGreaterThan(0)
    expect(cameraRange.far).toBeGreaterThan(cameraRange.maxDistance)
    expect(cameraRange.contextLost).toBe(false)
    await page.getByRole('button', { name: '收起监控区', exact: true }).click()
    await page.setViewportSize({ width: 1440, height: 900 })

    const result = await canvas.evaluate((element) => {
      const target = element as HTMLCanvasElement
      const rect = target.getBoundingClientRect()
      return {
        width: target.width,
        height: target.height,
        cssWidth: rect.width,
        cssHeight: rect.height,
        overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      }
    })
    expect(result.width).toBeGreaterThan(0)
    expect(result.height).toBeGreaterThan(0)
    expect(result.cssWidth).toBeGreaterThan(0)
    expect(result.cssHeight).toBeGreaterThan(500)
    expect(result.overflow).toBeLessThanOrEqual(1)
  })

  test('暂停后天体 tooltip 锚定在 Canvas 容器内', async ({ page }) => {
    await page.goto('/')
    await page.locator('.lab-parameters input').first().fill('Tooltip 响应式验收')
    await page.locator('.apply-button').first().click()
    await page.waitForURL(/\/experiments\//)
    await page.getByRole('button', { name: '暂停模拟' }).click()
    await expect(page.getByText('状态：PAUSED', { exact: true })).toBeVisible({ timeout: 10_000 })

    const canvas = page.locator('.simulation-canvas-dynamic')
    const canvasBox = await canvas.boundingBox()
    expect(canvasBox).not.toBeNull()
    await page.mouse.move(
      canvasBox!.x + canvasBox!.width / 2,
      canvasBox!.y + canvasBox!.height / 2,
    )
    const tooltip = page.getByTestId('body-hover-tooltip')
    await expect(tooltip).toBeVisible()
    const tooltipBox = await tooltip.boundingBox()
    const zoneBox = await page.locator('.scene-canvas-zone').boundingBox()
    expect(tooltipBox).not.toBeNull()
    expect(zoneBox).not.toBeNull()
    expect(tooltipBox!.x).toBeGreaterThanOrEqual(zoneBox!.x)
    expect(tooltipBox!.x + tooltipBox!.width).toBeLessThanOrEqual(zoneBox!.x + zoneBox!.width)
    expect(tooltipBox!.y).toBeGreaterThanOrEqual(zoneBox!.y)
    expect(tooltipBox!.y + tooltipBox!.height).toBeLessThanOrEqual(zoneBox!.y + zoneBox!.height)
  })
})

test('常见桌面尺寸和 DPR 不产生横向溢出或工具栏脱离', async ({ browser }) => {
  const cases = [
    { width: 1600, height: 900, deviceScaleFactor: 1 },
    { width: 1440, height: 900, deviceScaleFactor: 1.25 },
    { width: 1366, height: 768, deviceScaleFactor: 1.5 },
    { width: 2560, height: 1440, deviceScaleFactor: 2 },
  ]
  for (const current of cases) {
    const context = await browser.newContext({
      viewport: { width: current.width, height: current.height },
      deviceScaleFactor: current.deviceScaleFactor,
    })
    const page = await context.newPage()
    await page.goto('/')
    await page.waitForSelector('.simulation-canvas-wrap')
    const result = await page.evaluate(() => {
      const scene = document.querySelector('.lab-scene-card')!.getBoundingClientRect()
      const toolbar = document.querySelector('.scene-header-right')!.getBoundingClientRect()
      const canvas = document.querySelector('.simulation-canvas-dynamic') as HTMLCanvasElement
      return {
        documentOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
        toolbarContained: toolbar.left >= scene.left && toolbar.right <= scene.right,
        canvasWidth: canvas.width,
        canvasHeight: canvas.height,
      }
    })
    expect(result.documentOverflow, JSON.stringify(current)).toBeLessThanOrEqual(1)
    expect(result.toolbarContained, JSON.stringify(current)).toBe(true)
    expect(result.canvasWidth, JSON.stringify(current)).toBeGreaterThan(0)
    expect(result.canvasHeight, JSON.stringify(current)).toBeGreaterThan(0)
    await context.close()
  }
})
