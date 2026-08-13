import { expect, test } from '@playwright/test'

test('实验室使用单顶栏并可放大模拟视图', async ({ page }) => {
  await page.goto('/')

  await expect(page.locator('.app-header')).toHaveCount(0)
  await expect(page.locator('.lab-header')).toHaveCount(1)
  await expect(page.getByText('后端已连接', { exact: true })).toBeVisible()
  await expect(page.getByRole('progressbar', { name: '实验进度' })).toHaveCount(0)
  await expect(page.getByTestId('playback-timeline')).toBeVisible()

  const canvas = page.locator('.simulation-canvas-wrap')
  await expect(canvas.locator('.simulation-canvas-background')).toHaveCount(1)
  await expect(canvas.locator('.simulation-canvas-trail')).toHaveCount(1)
  await expect(canvas.locator('.simulation-canvas-dynamic')).toHaveCount(1)
  expect(await canvas.locator('canvas').evaluateAll((layers) => layers.map((layer) => getComputedStyle(layer).zIndex)))
    .toEqual(['2', '1', '0'])
  const before = await canvas.boundingBox()
  expect(before).not.toBeNull()

  await page.getByRole('button', { name: '收起监控区', exact: true }).click()
  await expect(page.getByRole('button', { name: '展开监控区', exact: true })).toBeVisible()
  await expect(page.getByTestId('playback-timeline')).toBeVisible()
  await expect(page.locator('.lab-kpis')).toBeHidden()
  await expect(page.locator('.chart-grid')).toBeHidden()

  const after = await canvas.boundingBox()
  expect(after).not.toBeNull()
  expect(after!.height).toBeGreaterThan(before!.height + 200)

  await page.getByRole('button', { name: '展开监控区', exact: true }).click()
  await expect(page.locator('.lab-kpis')).toBeVisible()
})

test('侧栏与下方监控区可以独立收纳', async ({ page }) => {
  await page.goto('/')

  const canvas = page.locator('.simulation-canvas-wrap')
  await expect(page.getByRole('button', { name: '2D', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByRole('button', { name: '3D', exact: true })).toHaveAttribute('aria-pressed', 'false')
  const initialWidth = (await canvas.boundingBox())?.width ?? 0
  await page.getByRole('button', { name: '收起侧栏', exact: true }).click()
  await expect(page.locator('aside.lab-parameters:visible')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '展开侧栏', exact: true })).toBeVisible()
  await expect.poll(async () => (await canvas.boundingBox())?.width ?? 0).toBeGreaterThan(initialWidth + 200)
  await expect(page.locator('.lab-kpis')).toBeVisible()

  await page.getByRole('button', { name: '收起监控区', exact: true }).click()
  await expect(page.locator('.lab-kpis')).toBeHidden()
  await expect(page.locator('aside.lab-parameters:visible')).toHaveCount(0)

  await page.getByRole('button', { name: '展开侧栏', exact: true }).click()
  await expect(page.locator('aside.lab-parameters:visible')).toHaveCount(1)
  await expect(page.locator('.lab-kpis')).toBeHidden()
})

test('Replay 时间线随浅色主题切换语义色板', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: '浅色' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')

  const lightColors = await page.getByTestId('playback-timeline').evaluate((timeline) => {
    const timecode = timeline.querySelector('.timeline-timecode') as HTMLElement
    const track = timeline.querySelector('.playback-track') as HTMLElement
    const select = timeline.querySelector('.replay-rate-label select') as HTMLElement
    return {
      panel: getComputedStyle(timeline).backgroundColor,
      timecode: getComputedStyle(timecode).backgroundColor,
      track: getComputedStyle(track).backgroundColor,
      select: getComputedStyle(select).backgroundColor,
    }
  })
  expect(lightColors).toEqual({
    panel: 'rgb(244, 247, 251)',
    timecode: 'rgb(255, 255, 255)',
    track: 'rgb(232, 238, 245)',
    select: 'rgb(255, 255, 255)',
  })

  await page.getByRole('button', { name: '深色' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect.poll(async () => page.getByTestId('playback-timeline').evaluate((timeline) => (
    getComputedStyle(timeline).backgroundColor
  ))).toBe('rgb(11, 17, 28)')
})

test('创建实验后实时状态可暂停并继续', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  await page.addInitScript(() => {
    const trackedWindow = window as typeof window & { __wsConstructCount: number }
    trackedWindow.__wsConstructCount = 0
    window.WebSocket = new Proxy(window.WebSocket, {
      construct(target, args) {
        trackedWindow.__wsConstructCount += 1
        return Reflect.construct(target, args)
      },
    })
  })
  await page.goto('/')
  await page.getByLabel('实验名称', { exact: true }).fill('Playwright 验收实验')
  await page.getByLabel('最大步数', { exact: true }).fill('2000000')
  await page.getByRole('button', { name: '应用参数并开始实验', exact: true }).click()
  const warning = page.getByRole('dialog', { name: '存在运行风险' })
  await expect(warning).toBeVisible()
  await warning.getByRole('button', { name: '理解风险，仍按原配置运行', exact: true }).click()

  await expect(page).toHaveURL(/\/experiments\/[0-9a-f-]+$/)
  await expect(page.getByText('状态：RUNNING', { exact: true })).toBeVisible({ timeout: 10_000 })
  await expect.poll(async () => Number(
    await page.getByRole('slider', { name: '历史时间轴' }).getAttribute('aria-valuemax'),
  )).toBeGreaterThan(0)
  await expect(page.getByText('计划终点 2,000,000 步', { exact: true })).toBeVisible()

  const experimentUrl = page.url()
  const progressBeforeSwitch = Number(
    await page.getByRole('slider', { name: '历史时间轴' }).getAttribute('aria-valuemax'),
  )
  const socketCount = await page.evaluate(() => (
    window as typeof window & { __wsConstructCount: number }
  ).__wsConstructCount)

  await page.getByRole('button', { name: '3D', exact: true }).click()
  const canvas3d = page.getByTestId('simulation-scene-3d-canvas')
  await expect(canvas3d).toBeVisible()
  const dimensions = await canvas3d.evaluate((element) => {
    const canvas = element as HTMLCanvasElement
    return { width: canvas.width, height: canvas.height }
  })
  expect(dimensions.width).toBeGreaterThan(0)
  expect(dimensions.height).toBeGreaterThan(0)
  await expect.poll(async () => Number(await canvas3d.getAttribute('data-body-visual-count'))).toBeGreaterThan(0)
  await expect(canvas3d).toHaveAttribute('data-body-geometry', '64x48-shared')

  await page.getByRole('button', { name: '2D', exact: true }).click()
  await expect(page.locator('.simulation-canvas-wrap canvas')).toHaveCount(3)
  await page.getByRole('button', { name: '3D', exact: true }).click()
  await expect(page.getByTestId('simulation-scene-3d-canvas')).toBeVisible()
  expect(page.url()).toBe(experimentUrl)
  await expect(page.getByText('状态：RUNNING', { exact: true })).toBeVisible()
  expect(Number(
    await page.getByRole('slider', { name: '历史时间轴' }).getAttribute('aria-valuemax'),
  )).toBeGreaterThanOrEqual(progressBeforeSwitch)
  expect(await page.evaluate(() => (
    window as typeof window & { __wsConstructCount: number }
  ).__wsConstructCount)).toBe(socketCount)

  await page.getByRole('button', { name: '2D', exact: true }).click()

  await page.getByRole('button', { name: '暂停模拟' }).click()
  await expect(page.getByText('状态：PAUSED', { exact: true })).toBeVisible({ timeout: 10_000 })

  await page.getByRole('button', { name: '后退一帧' }).click()
  await expect(page.getByRole('button', { name: '返回实时' })).toBeVisible()
  await expect(page.getByLabel('Replay 倍速')).toHaveValue('1')
  await page.getByLabel('Replay 倍速').selectOption('2')
  await expect(page.getByLabel('Replay 倍速')).toHaveValue('2')
  const cursorBeforeKeyboard = Number(await page.getByRole('slider', { name: '历史时间轴' }).getAttribute('aria-valuenow'))
  await page.getByLabel('Replay 时间线').focus()
  await page.keyboard.press('ArrowLeft')
  await expect.poll(async () => Number(
    await page.getByRole('slider', { name: '历史时间轴' }).getAttribute('aria-valuenow'),
  )).toBeLessThan(cursorBeforeKeyboard)
  await page.keyboard.press('Space')
  await expect(page.getByRole('button', { name: '暂停历史回放' })).toBeVisible()
  await page.keyboard.press('Space')
  await expect(page.getByRole('button', { name: '播放历史' })).toBeVisible()
  await page.getByRole('button', { name: '返回实时' }).click()

  await page.getByRole('button', { name: '继续模拟' }).click()
  await expect(page.getByText('状态：RUNNING', { exact: true })).toBeVisible({ timeout: 10_000 })
  expect(pageErrors).toEqual([])
})
