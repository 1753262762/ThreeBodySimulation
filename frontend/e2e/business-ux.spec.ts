import { expect, test } from '@playwright/test'

test('Warning 二次确认与同参数去重：返回修改不创建，重复提交只保留一次', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('实验名称', { exact: true }).fill('Warning 验收实验')
  // 软化长度为 0 触发 SOFTENING_TOO_SMALL 风险提示。
  await page.getByLabel('软化长度', { exact: true }).fill('0')
  await page.getByRole('button', { name: '应用参数并开始实验', exact: true }).click()

  const dialog = page.getByRole('dialog', { name: '存在运行风险' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('软化长度')

  // 返回修改：弹窗关闭、不创建实验。
  await dialog.getByRole('button', { name: '返回修改', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.locator('.queue-item')).toHaveCount(0)

  // 再次提交并仍然运行：只创建一个实验并进入详情页。
  await page.getByRole('button', { name: '应用参数并开始实验', exact: true }).click()
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: '理解风险，仍按原配置运行', exact: true }).click()

  await expect(page).toHaveURL(/\/experiments\/[0-9a-f-]+$/)
  await expect(page.getByText('状态：RUNNING', { exact: true })).toBeVisible({ timeout: 10_000 })

  const experimentUrl = page.url()
  await page.getByLabel('实验名称', { exact: true }).fill('同参数仅改名')
  await page.getByRole('button', { name: '应用参数并开始实验', exact: true }).click()
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: '理解风险，仍按原配置运行', exact: true }).click()
  await expect(page).toHaveURL(experimentUrl)
  await expect(page.locator('.action-notice:visible').filter({
    hasText: '已存在同参数实验，已直接打开，未重复创建或保存。',
  })).toBeVisible()

  await page.getByRole('button', { name: '切换到队列', exact: true }).click()
  await expect(page.locator('.queue-item')).toHaveCount(1)
})

test('四种观察模式与浅深主题切换', async ({ page }) => {
  await page.goto('/')
  // FOLLOW_BODY 需要有效目标天体，先创建一个默认实验。
  await page.getByLabel('实验名称', { exact: true }).fill('观察模式实验')
  await page.getByRole('button', { name: '应用参数并开始实验', exact: true }).click()
  const warning = page.getByRole('dialog', { name: '存在运行风险' })
  await Promise.race([
    warning.waitFor({ state: 'visible' }),
    page.waitForURL(/\/experiments\/[0-9a-f-]+$/),
  ])
  if (await warning.isVisible()) {
    await warning.getByRole('button', { name: '理解风险，仍按原配置运行', exact: true }).click()
  }
  await expect(page).toHaveURL(/\/experiments\/[0-9a-f-]+$/)
  await expect(page.getByText('状态：RUNNING', { exact: true })).toBeVisible({ timeout: 10_000 })

  const modes = page.getByRole('group', { name: '观察模式' })
  await expect(modes.getByRole('button', { name: '自由' })).toHaveAttribute('aria-pressed', 'true')

  await modes.getByRole('button', { name: '质心' }).click()
  await expect(modes.getByRole('button', { name: '质心' })).toHaveAttribute('aria-pressed', 'true')

  await modes.getByRole('button', { name: '跟随' }).click()
  await expect(page.getByLabel('选择跟随天体')).toBeVisible()

  await modes.getByRole('button', { name: '适应' }).click()
  await expect(modes.getByRole('button', { name: '适应' })).toHaveAttribute('aria-pressed', 'true')

  await modes.getByRole('button', { name: '自由' }).click()
  await expect(page.getByLabel('选择跟随天体')).toBeHidden()
  await expect(modes.getByRole('button', { name: '自由' })).toHaveAttribute('aria-pressed', 'true')

  const theme = page.getByRole('group', { name: '主题' })
  await theme.getByRole('button', { name: '浅色' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')

  await theme.getByRole('button', { name: '深色' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')

  await theme.getByRole('button', { name: '跟随系统' }).click()
  await expect(theme.getByRole('button', { name: '跟随系统' })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.locator('html')).toHaveAttribute('data-theme', /^(light|dark)$/)
})
