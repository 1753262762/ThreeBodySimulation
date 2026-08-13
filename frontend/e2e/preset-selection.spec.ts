import { expect, test } from '@playwright/test'

test('默认应用方案 A 并提供全部内置方案', async ({ page }) => {
  await page.goto('/')

  const presetSelect = page.locator('#preset-select')
  await expect(presetSelect).toHaveValue('A')
  await expect(page.locator('#exp-name')).toHaveValue('方案 A：稳定层级三星行星系统')
  await expect(presetSelect.locator('option')).toHaveCount(11)

  await presetSelect.selectOption('D')
  await expect(page.locator('#exp-name')).toHaveValue('方案 D：20 星层级双星压力测试')
  await expect(page.locator('.body-row')).toHaveCount(20)

  await presetSelect.selectOption('E')
  await expect(page.locator('#exp-name')).toHaveValue('方案 E：真实太阳系 J2000 三维版')
  await expect(page.locator('.body-row')).toHaveCount(9)

  await presetSelect.selectOption('F')
  await expect(page.locator('#exp-name')).toHaveValue('方案 F：地月系统稳定双体轨道')
  await expect(page.locator('.body-row')).toHaveCount(2)

  await presetSelect.selectOption('G')
  await expect(page.locator('#exp-name')).toHaveValue('3D展示:正交双轨道系统')
  await expect(page.locator('.body-row')).toHaveCount(3)

  await presetSelect.selectOption('H')
  await expect(page.locator('#exp-name')).toHaveValue('3D展示:螺旋穿越系统')
  await expect(page.locator('.body-row')).toHaveCount(3)

  await presetSelect.selectOption('I')
  await expect(page.locator('#exp-name')).toHaveValue('3D展示:三维混沌近遇')
  await expect(page.locator('.body-row')).toHaveCount(3)

  await presetSelect.selectOption('J')
  await expect(page.locator('#exp-name')).toHaveValue('3D展示:四体三平面正交轨道')
  await expect(page.locator('.body-row')).toHaveCount(4)
})
