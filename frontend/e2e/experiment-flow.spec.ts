import { expect, test } from '@playwright/test'

test('创建实验后实时状态可暂停并继续', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('实验名称', { exact: true }).fill('Playwright 验收实验')
  await page.getByRole('button', { name: '应用参数并开始实验', exact: true }).click()

  await expect(page).toHaveURL(/\/experiments\/[0-9a-f-]+$/)
  await expect(page.getByText('状态：RUNNING', { exact: true })).toBeVisible({ timeout: 10_000 })

  await page.getByTitle('暂停').click()
  await expect(page.getByText('状态：PAUSED', { exact: true })).toBeVisible({ timeout: 10_000 })

  await page.getByTitle('继续').click()
  await expect(page.getByText('状态：RUNNING', { exact: true })).toBeVisible({ timeout: 10_000 })
})
