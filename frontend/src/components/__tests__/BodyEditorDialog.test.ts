import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import type { BodyDraft, ConfigDraft } from '../../lib/configDraft'
import BodyEditorDialog from '../BodyEditorDialog.vue'

const firstBody: BodyDraft = {
  rowId: 'body-1', id: 'one', name: '天体一', color: '#ffc857', mass: '10',
  positionX: '0', positionY: '0', positionZ: '0',
  velocityX: '0', velocityY: '1', velocityZ: '0',
}

const secondBody: BodyDraft = {
  rowId: 'body-2', id: 'two', name: '天体二', color: '#59c3ff', mass: '20',
  positionX: '10', positionY: '0', positionZ: '0',
  velocityX: '0', velocityY: '-1', velocityZ: '0',
}

function configDraft(): ConfigDraft {
  return {
    name: '测试实验',
    bodies: [{ ...firstBody }, { ...secondBody }],
    timeStep: '1', gravitationalConstant: '1', softeningLength: '0.1',
    endCondition: 'MAX_STEPS', maxSteps: '100', targetSimulationTime: '',
  }
}

let wrapper: VueWrapper | null = null

function dialog(): HTMLElement {
  return document.body.querySelector<HTMLElement>('.body-editor-dialog') as HTMLElement
}

function input(id: string): HTMLInputElement {
  return document.body.querySelector<HTMLInputElement>(`#${id}`) as HTMLInputElement
}

async function setInput(id: string, value: string): Promise<void> {
  const element = input(id)
  element.value = value
  element.dispatchEvent(new Event('input', { bubbles: true }))
  await wrapper?.vm.$nextTick()
}

function mountDialog(body: BodyDraft = secondBody, mode: 'add' | 'edit' = 'edit'): VueWrapper {
  wrapper = mount(BodyEditorDialog, {
    props: { open: true, mode, body: { ...body }, configDraft: configDraft(), unitSystem: 'SI' },
    attachTo: document.body,
  })
  return wrapper
}

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('BodyEditorDialog', () => {
  it('使用临时副本编辑并只在保存时提交', async () => {
    const mounted = mountDialog()
    await mounted.vm.$nextTick()

    expect(input('body-editor-name').value).toBe('天体二')
    await setInput('body-editor-name', '修改后的天体')
    expect(secondBody.name).toBe('天体二')

    document.body.querySelector<HTMLButtonElement>('.body-editor-dialog-actions button:last-child')?.click()
    await mounted.vm.$nextTick()
    expect(mounted.emitted('save')?.[0]?.[0]).toMatchObject({ rowId: 'body-2', name: '修改后的天体' })
  })

  it('即时显示质量、坐标和重合位置错误并阻止保存', async () => {
    mountDialog()
    await setInput('body-editor-mass', '0')
    expect(dialog().textContent).toContain('质量必须为正数')
    expect(document.body.querySelector<HTMLButtonElement>('.body-editor-dialog-actions button:last-child')?.disabled).toBe(true)

    await setInput('body-editor-mass', '20')
    await setInput('body-editor-position-x', 'not-a-number')
    expect(dialog().textContent).toContain('必须是有限数')

    await setInput('body-editor-position-x', '0')
    expect(dialog().textContent).toContain('初始位置完全重合')
    expect(document.body.querySelector<HTMLButtonElement>('.body-editor-dialog-actions button:last-child')?.disabled).toBe(true)
  })

  it('未保存关闭时要求确认，并支持继续编辑或放弃修改', async () => {
    const mounted = mountDialog()
    await setInput('body-editor-name', '尚未保存')

    document.body.querySelector<HTMLButtonElement>('.body-editor-dialog-actions .secondary')?.click()
    await mounted.vm.$nextTick()
    expect(document.body.querySelector('[role="alertdialog"]')).not.toBeNull()

    document.body.querySelector<HTMLButtonElement>('[role="alertdialog"] .secondary')?.click()
    await mounted.vm.$nextTick()
    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull()
    expect(dialog()).not.toBeNull()

    dialog().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await mounted.vm.$nextTick()
    document.body.querySelector<HTMLButtonElement>('[role="alertdialog"] .danger')?.click()
    await mounted.vm.$nextTick()
    expect(mounted.emitted('close')).toHaveLength(1)
    expect(mounted.emitted('save')).toBeUndefined()
  })

  it('约束焦点并在关闭后把焦点返回触发按钮', async () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    const mounted = mountDialog()
    await mounted.vm.$nextTick()
    await mounted.vm.$nextTick()
    expect(document.activeElement).toBe(input('body-editor-name'))

    const close = document.body.querySelector<HTMLButtonElement>('.body-editor-close') as HTMLButtonElement
    const save = document.body.querySelector<HTMLButtonElement>('.body-editor-dialog-actions button:last-child') as HTMLButtonElement
    save.focus()
    save.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }))
    expect(document.activeElement).toBe(close)
    close.focus()
    close.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true }))
    expect(document.activeElement).toBe(save)

    await mounted.setProps({ open: false })
    await mounted.vm.$nextTick()
    expect(document.activeElement).toBe(opener)
  })
})
