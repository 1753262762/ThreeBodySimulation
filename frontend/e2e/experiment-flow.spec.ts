import { expect, test } from '@playwright/test'

test('实验室使用单顶栏并可放大模拟视图', async ({ page }) => {
  await page.goto('/')

  await expect(page.locator('.app-header')).toHaveCount(0)
  await expect(page.locator('.lab-header')).toHaveCount(1)
  await expect(page.getByText('后端已连接', { exact: true })).toBeVisible()
  await expect(page.getByRole('progressbar', { name: '实验进度' })).toHaveCount(0)
  await expect(page.getByText('创建实验后显示进度', { exact: true })).toBeVisible()

  const canvas = page.locator('.simulation-canvas-wrap')
  await expect(canvas.locator('.simulation-canvas-background')).toHaveCount(1)
  await expect(canvas.locator('.simulation-canvas-trail')).toHaveCount(1)
  await expect(canvas.locator('.simulation-canvas-dynamic')).toHaveCount(1)
  expect(await canvas.locator('canvas').evaluateAll((layers) => layers.map((layer) => getComputedStyle(layer).zIndex)))
    .toEqual(['2', '1', '0'])
  const before = await canvas.boundingBox()
  expect(before).not.toBeNull()

  await page.getByRole('button', { name: '放大视图', exact: true }).click()
  await expect(page.getByRole('button', { name: '还原布局', exact: true })).toBeVisible()
  await expect(page.locator('.lab-kpis')).toBeHidden()
  await expect(page.locator('.chart-row').first()).toBeHidden()

  const after = await canvas.boundingBox()
  expect(after).not.toBeNull()
  expect(after!.height).toBeGreaterThan(before!.height + 200)

  await page.getByRole('button', { name: '还原布局', exact: true }).click()
  await expect(page.locator('.lab-kpis')).toBeVisible()
})

test('创建实验后实时状态可暂停并继续', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('实验名称', { exact: true }).fill('Playwright 验收实验')
  await page.getByLabel('最大步数', { exact: true }).fill('2000000')
  await page.getByRole('button', { name: '应用参数并开始实验', exact: true }).click()

  await expect(page).toHaveURL(/\/experiments\/[0-9a-f-]+$/)
  await expect(page.getByText('状态：RUNNING', { exact: true })).toBeVisible({ timeout: 10_000 })
  await expect.poll(async () => Number(
    await page.getByRole('progressbar', { name: '实验进度' }).getAttribute('aria-valuenow'),
  )).toBeGreaterThan(0)
  await expect(page.locator('.progress-detail')).toContainText('/ 2,000,000 步')

  await page.getByTitle('暂停').click()
  await expect(page.getByText('状态：PAUSED', { exact: true })).toBeVisible({ timeout: 10_000 })

  await page.getByTitle('继续').click()
  await expect(page.getByText('状态：RUNNING', { exact: true })).toBeVisible({ timeout: 10_000 })
})
