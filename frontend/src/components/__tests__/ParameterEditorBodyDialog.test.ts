import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { MAX_BODY_COUNT } from '../../contracts'
import { createBodyDraft } from '../../lib/configDraft'
import { useDraftStore } from '../../stores/draft'
import ParameterEditor from '../ParameterEditor.vue'

let wrapper: VueWrapper | null = null

function buttonByText(text: string): HTMLButtonElement {
  return wrapper?.findAll('button').find((button) => button.text().includes(text))?.element as HTMLButtonElement
}

async function setDialogInput(id: string, value: string): Promise<void> {
  const input = document.body.querySelector<HTMLInputElement>(`#${id}`) as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await wrapper?.vm.$nextTick()
}

describe('ParameterEditor 天体弹窗集成', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.body.innerHTML = ''
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    document.body.innerHTML = ''
  })

  it('新增取消不追加，保存后才追加天体', async () => {
    const store = useDraftStore()
    wrapper = mount(ParameterEditor, { attachTo: document.body })
    const initialCount = store.draft.bodies.length

    buttonByText('添加').click()
    await wrapper.vm.$nextTick()
    expect(store.draft.bodies).toHaveLength(initialCount)
    document.body.querySelector<HTMLButtonElement>('.body-editor-dialog-actions .secondary')?.click()
    await wrapper.vm.$nextTick()
    expect(store.draft.bodies).toHaveLength(initialCount)

    buttonByText('添加').click()
    await wrapper.vm.$nextTick()
    await setDialogInput('body-editor-mass', '1')
    await setDialogInput('body-editor-position-x', '123456789')
    document.body.querySelector<HTMLButtonElement>('.body-editor-dialog-actions button:last-child')?.click()
    await wrapper.vm.$nextTick()
    expect(store.draft.bodies).toHaveLength(initialCount + 1)
  })

  it('编辑保存后替换原天体，复制仍不打开弹窗', async () => {
    const store = useDraftStore()
    wrapper = mount(ParameterEditor, { attachTo: document.body })
    const originalRowId = store.draft.bodies[0]?.rowId

    await wrapper.findAll<HTMLButtonElement>('button[aria-label="编辑天体参数"]')[0]?.trigger('click')
    await setDialogInput('body-editor-name', '重命名天体')
    document.body.querySelector<HTMLButtonElement>('.body-editor-dialog-actions button:last-child')?.click()
    await wrapper.vm.$nextTick()
    expect(store.draft.bodies[0]?.rowId).toBe(originalRowId)
    expect(store.draft.bodies[0]?.name).toBe('重命名天体')

    await wrapper.findAll<HTMLButtonElement>('button[aria-label="复制天体"]')[0]?.trigger('click')
    expect(document.body.querySelector('.body-editor-dialog')).toBeNull()
  })

  it('达到天体数量上限时禁用添加', () => {
    const store = useDraftStore()
    while (store.draft.bodies.length < MAX_BODY_COUNT) {
      const body = createBodyDraft(store.draft.bodies.length)
      body.mass = '1'
      body.positionX = String(store.draft.bodies.length * 100)
      store.draft.bodies.push(body)
    }
    wrapper = mount(ParameterEditor)
    expect(buttonByText('添加').disabled).toBe(true)
  })

  it('不再渲染未实现的轨迹显示设置', () => {
    wrapper = mount(ParameterEditor)

    expect(wrapper.text()).not.toContain('显示设置')
    expect(wrapper.text()).not.toContain('轨迹线')
    expect(wrapper.text()).not.toContain('2,000 点')
  })
})
