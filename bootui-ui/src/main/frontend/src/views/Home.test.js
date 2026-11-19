import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {defineComponent, h, KeepAlive, ref} from 'vue'
import {createMemoryHistory, createRouter} from 'vue-router'
import {routes} from '../routes.js'
import {homeAgentGuide, homeGuide, homePanelDescriptions} from '../utils/homeGuide.js'
import Home from './Home.vue'

const panelRoutes = routes.filter((route) => route.name && route.meta?.group && !route.meta.uiOnly)
const completeManifest = (platform = 'spring-boot') => ({
  platform,
  panels: panelRoutes.map((route) => ({id: route.name, available: true, enabled: true, readOnly: false}))
})
const wrappers = []

async function mountHome({panels = ref(completeManifest()), keptAlive = false, realTransitions = false} = {}) {
  const router = createRouter({
    history: createMemoryHistory('/console/'),
    routes: routes.map((route) => (route.redirect ? route : {...route, component: {template: '<div />'}}))
  })
  await router.push('/home')
  const show = ref(true)
  const Host = defineComponent({
    setup() {
      return () => h(KeepAlive, null, {default: () => (show.value ? h(Home) : h('div', 'away'))})
    }
  })
  const wrapper = mount(keptAlive ? Host : Home, {
    attachTo: document.body,
    global: {plugins: [router], provide: {panels}, stubs: {Transition: !realTransitions}}
  })
  wrappers.push(wrapper)
  await flushPromises()
  return {wrapper, router, panels, show}
}

async function openTopic(wrapper, section, topic) {
  if (wrapper.find('[data-home-back="root"]').exists()) {
    await wrapper.get('[data-home-back="root"]').trigger('click')
  }
  await wrapper.get(`[data-home-choice="${section}"]`).trigger('click')
  await wrapper.get(`[data-home-choice="${topic}"]`).trigger('click')
}

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('Home decision guides', () => {
  it('initially shows only three diagnostic choices and an agent guide, each with a short explanation', async () => {
    const {wrapper} = await mountHome()
    expect(wrapper.findAll('.home-guide-choice')).toHaveLength(4)
    expect(wrapper.findAll('.home-guide-choice-title').map((item) => item.text())).toEqual([
      ...homeGuide.map((section) => section.title),
      homeAgentGuide.title
    ])
    for (const choice of wrapper.findAll('.home-guide-choice')) {
      expect(choice.get('.home-guide-explanation').text().length).toBeGreaterThan(20)
      expect(document.getElementById(choice.attributes('aria-labelledby'))).toBeTruthy()
      expect(document.getElementById(choice.attributes('aria-describedby'))).toBeTruthy()
    }
    expect(wrapper.find('[data-home-panel]').exists()).toBe(false)
    expect(wrapper.find('.home-guide-path').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Toggle auto-refresh"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Refresh panel"]').exists()).toBe(false)
  })

  it('progressively reaches exactly the existing panel catalogue through explained custom-base links', async () => {
    const placements = homeGuide.flatMap((section) => section.topics.flatMap((topic) => topic.panels))
    expect(new Set(placements)).toEqual(new Set(panelRoutes.map((route) => route.name)))
    expect(Object.keys(homePanelDescriptions).sort()).toEqual(panelRoutes.map((route) => route.name).sort())
    const {wrapper} = await mountHome()
    const reached = []
    for (const section of homeGuide) {
      await wrapper.get(`[data-home-choice="${section.id}"]`).trigger('click')
      expect(wrapper.findAll('.home-guide-choice')).toHaveLength(section.topics.length)
      expect(wrapper.find('[data-home-panel]').exists()).toBe(false)
      for (const topic of section.topics) {
        await wrapper.get(`[data-home-choice="${topic.id}"]`).trigger('click')
        expect(wrapper.find('[data-home-choice]').exists()).toBe(false)
        expect(wrapper.get('.home-guide-step-heading').text()).toBe(topic.title)
        expect(wrapper.findAll('[data-home-panel]')).toHaveLength(topic.panels.length)
        for (const name of topic.panels) {
          const entry = wrapper.get(`[data-home-panel="${name}"]`)
          const route = panelRoutes.find((candidate) => candidate.name === name)
          expect(entry.get('a').attributes('href')).toBe(`/console${route.path}`)
          expect(entry.get('.home-guide-explanation').text().length).toBeGreaterThan(15)
          expect(entry.get('a .home-guide-arrow').exists()).toBe(true)
          reached.push(name)
        }
        await wrapper.get('[data-home-back="topics"]').trigger('click')
      }
      await wrapper.get('[data-home-back="root"]').trigger('click')
    }
    expect(reached).toEqual(placements)
  })

  it('does not fetch, scan, poll or mount destination panels while exploring', async () => {
    vi.useFakeTimers()
    const fetch = vi.fn(() => {
      throw new Error('Home must not collect panel evidence')
    })

    vi.stubGlobal('fetch', fetch)
    const {wrapper, router} = await mountHome()
    await openTopic(wrapper, 'understand', 'memory')
    await vi.advanceTimersByTimeAsync(60_000)
    expect(router.currentRoute.value.path).toBe('/home')
    expect(fetch).not.toHaveBeenCalled()
    await wrapper.get('[data-home-panel="jvm-tuning"] a').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/jvm-tuning')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('returns to the initial choices from the header icon or title without collecting evidence', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const {wrapper} = await mountHome()
    await openTopic(wrapper, 'behavior', 'errors')
    await wrapper.get('.panel-header__title-action .bi-house').trigger('click')
    expect(wrapper.findAll('[data-home-choice]')).toHaveLength(4)
    expect(wrapper.find('.home-guide-path').exists()).toBe(false)
    expect(wrapper.find('[data-home-panel]').exists()).toBe(false)

    await wrapper.get('[data-home-choice="agents"]').trigger('click')
    await wrapper.get('.panel-header__title-action').trigger('click')
    expect(wrapper.findAll('[data-home-choice]')).toHaveLength(4)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('updates late capabilities and keeps disabled, missing, read-only and agent destinations honest', async () => {
    const panels = ref(null)
    const {wrapper} = await mountHome({panels})
    await openTopic(wrapper, 'understand', 'wiring')
    expect(wrapper.find('a.home-guide-panel-link').exists()).toBe(false)
    expect(wrapper.get('[data-home-panel="config"]').text()).toContain('Checking availability')
    panels.value = completeManifest('quarkus')
    panels.value.panels = panels.value.panels.filter((panel) => panel.id !== 'mysql')
    Object.assign(
      panels.value.panels.find((panel) => panel.id === 'config'),
      {enabled: false}
    )
    Object.assign(
      panels.value.panels.find((panel) => panel.id === 'code-paths'),
      {
        available: false,
        unavailableReason: 'Attach the Java agent to inspect code paths.'
      }
    )
    Object.assign(
      panels.value.panels.find((panel) => panel.id === 'memory'),
      {
        readOnly: true,
        readOnlyReason: 'Read-only by local policy.'
      }
    )
    await flushPromises()
    expect(wrapper.get('[data-home-panel="config"]').text()).toContain('bootui.panels.config.enabled=false')
    expect(wrapper.get('[data-home-panel="config"]').find('a').exists()).toBe(false)
    const disabled = wrapper.get('[data-home-panel="config"] [aria-disabled="true"]')
    expect(document.getElementById(disabled.attributes('aria-describedby')).textContent).toContain(
      'bootui.panels.config.enabled=false'
    )
    await openTopic(wrapper, 'review', 'design')
    expect(wrapper.get('[data-home-panel="spring"] .home-guide-choice-title').text()).toBe('Quarkus')
    await openTopic(wrapper, 'behavior', 'database')
    expect(wrapper.get('[data-home-panel="mysql"]').text()).toContain('not reported')
    expect(wrapper.get('[data-home-panel="mysql"]').find('a').exists()).toBe(false)
    await openTopic(wrapper, 'behavior', 'code')
    expect(wrapper.get('[data-home-panel="code-paths"]').text()).toContain('Attach the Java agent')
    expect(wrapper.get('[data-home-panel="code-paths"]').find('a').exists()).toBe(false)
    await openTopic(wrapper, 'understand', 'memory')
    expect(wrapper.get('[data-home-panel="memory"]').text()).toContain('Read-only by local policy.')
    expect(wrapper.get('[data-home-panel="memory"] a').exists()).toBe(true)
    panels.value.panels.find((panel) => panel.id === 'memory').available = false
    await flushPromises()
    expect(wrapper.get('[data-home-panel="memory"]').find('a').exists()).toBe(false)
  })

  it('preserves the chosen level and focus through capability updates and cached return navigation', async () => {
    const {wrapper, show, panels} = await mountHome({keptAlive: true})
    await openTopic(wrapper, 'understand', 'memory')
    const link = wrapper.get('[data-home-panel="jvm-tuning"] a')
    link.element.focus()
    panels.value = completeManifest()
    await flushPromises()
    expect(document.activeElement).toBe(link.element)
    expect(wrapper.get('.home-guide-step-heading').text()).toBe('Memory and JVM')
    show.value = false
    await flushPromises()
    show.value = true
    await flushPromises()
    expect(wrapper.get('.home-guide-step-heading').text()).toBe('Memory and JVM')
    expect(wrapper.get('[data-home-panel="memory"] a').exists()).toBe(true)
  })

  it('moves focus to the current heading when policy revokes a focused destination', async () => {
    const {wrapper, panels} = await mountHome()
    await openTopic(wrapper, 'understand', 'wiring')
    const link = wrapper.get('[data-home-panel="config"] a')
    link.element.focus()
    panels.value.panels.find((panel) => panel.id === 'config').enabled = false
    await flushPromises()
    expect(wrapper.get('[data-home-panel="config"]').find('a').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('.home-guide-step-heading').element)
    expect(wrapper.get('.home-guide-step-heading').text()).toBe('Configuration and wiring')
  })

  it('explains agent delegation and links existing skills, playbooks and policy-gated local setup', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const {wrapper, panels, router} = await mountHome()
    panels.value.panels.find((panel) => panel.id === 'mcp-server').enabled = false
    await wrapper.get('[data-home-choice="agents"]').trigger('click')
    expect(wrapper.text()).toContain('You decide what it may run or change')
    expect(wrapper.text()).toContain('Six playbooks')
    expect(wrapper.find('a[target="_blank"]').exists()).toBe(false)
    expect(wrapper.get('[data-home-panel="mcp-server"]').find('a').exists()).toBe(false)
    expect(wrapper.get('[data-home-panel="cli"] a').attributes('href')).toBe('/console/cli')
    for (const resource of homeAgentGuide.resources) {
      await wrapper.get(`[data-home-choice="${resource.id}"]`).trigger('click')
      expect(wrapper.get('.home-guide-step-heading').text()).toBe(resource.title)
      expect(wrapper.get('.home-guide-resource').text()).toContain(
        resource.id === 'agent-skill' ? 'plain HTTP' : 'existing evidence'
      )
      const link = wrapper.get('a[target="_blank"]')
      expect(wrapper.findAll('a[target="_blank"]')).toHaveLength(1)
      expect(link.attributes('href')).toBe(resource.href)
      expect(link.attributes('rel')).toBe('noopener noreferrer')
      expect(link.attributes('aria-label')).toContain('opens in a new tab')
      expect(wrapper.get('.home-guide-resource').text()).toContain('Find the full documentation at:')
      if (resource.id === 'agent-skill') {
        expect(wrapper.findAll('.home-guide-resource pre code').map((code) => code.text())).toEqual([
          'npx skills add https://github.com/jdubois/boot-ui/tree/main/skills/bootui',
          'gh skill preview jdubois/boot-ui skills/bootui\ngh skill install jdubois/boot-ui skills/bootui'
        ])
      }
      expect(wrapper.find('[data-home-panel]').exists()).toBe(false)
      expect(router.currentRoute.value.path).toBe('/home')
      await wrapper.get('[data-home-back="topics"]').trigger('click')
      expect(wrapper.find('a[target="_blank"]').exists()).toBe(false)
    }
    expect(fetch).not.toHaveBeenCalled()
  })

  it('copies each complete command block only on request and confirms the selected block', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const {wrapper} = await mountHome()
    await openTopic(wrapper, 'agents', 'agent-skill')
    expect(writeText).not.toHaveBeenCalled()
    const commands = wrapper.findAll('.home-guide-command')
    expect(commands).toHaveLength(2)
    for (const command of commands) {
      await command.get('button[aria-label^="Copy commands:"]').trigger('click')
      await flushPromises()
      expect(writeText).toHaveBeenLastCalledWith(command.get('code').text())
      expect(command.get('[role="status"]').text()).toBe('Copied!')
      expect(command.find('.bi-check-lg').exists()).toBe(true)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    }
    expect(writeText).toHaveBeenCalledTimes(2)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('explains a denied copy without stale success feedback or removing selectable commands', async () => {
    const writeText = vi.fn().mockResolvedValueOnce().mockRejectedValueOnce(new Error('denied'))
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    const {wrapper} = await mountHome()
    await openTopic(wrapper, 'agents', 'agent-skill')
    const command = wrapper.get('.home-guide-command')
    const button = command.get('button')
    await button.trigger('click')
    await flushPromises()
    expect(command.get('[role="status"]').text()).toBe('Copied!')
    await button.trigger('click')
    await flushPromises()
    expect(command.get('[role="status"]').text()).toBe('')
    expect(command.find('.bi-check-lg').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('Select the commands and copy them manually')
    expect(command.get('pre').attributes('tabindex')).toBe('0')
    expect(command.get('code').text()).toBe('npx skills add https://github.com/jdubois/boot-ui/tree/main/skills/bootui')
  })

  it('clears copy feedback when leaving the step or deactivating Home', async () => {
    vi.stubGlobal('navigator', {clipboard: {writeText: vi.fn().mockRejectedValue(new Error('denied'))}})
    const {wrapper, show} = await mountHome({keptAlive: true})
    await openTopic(wrapper, 'agents', 'agent-skill')
    await wrapper.get('.home-guide-copy').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    await wrapper.get('[data-home-back="topics"]').trigger('click')
    await wrapper.get('[data-home-choice="agent-skill"]').trigger('click')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    await wrapper.get('.home-guide-copy').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    show.value = false
    await flushPromises()
    show.value = true
    await flushPromises()
    expect(wrapper.get('.home-guide-step-heading').text()).toBe(
      homeAgentGuide.resources.find((resource) => resource.id === 'agent-skill').title
    )
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('makes transitioning views inert and restores keyboard focus after forward and back motion', async () => {
    vi.useFakeTimers()
    const {wrapper} = await mountHome({realTransitions: true})
    const firstStep = wrapper.get('.home-guide-step')
    await wrapper.get('[data-home-choice="understand"]').trigger('click')
    expect(firstStep.element.inert).toBe(true)
    await vi.advanceTimersByTimeAsync(600)
    expect(wrapper.get('.home-guide-step').element.inert).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('.home-guide-step-heading').element)
    await wrapper.get('[data-home-choice="memory"]').trigger('click')
    await vi.advanceTimersByTimeAsync(600)
    expect(document.activeElement).toBe(wrapper.get('.home-guide-step-heading').element)
    expect(wrapper.get('.home-guide-step-heading').text()).toBe('Memory and JVM')
    await wrapper.get('[data-home-back="topics"]').trigger('click')
    await vi.advanceTimersByTimeAsync(600)
    expect(document.activeElement).toBe(wrapper.get('[data-home-choice="memory"]').element)
    await wrapper.get('[data-home-back="root"]').trigger('click')
    await vi.advanceTimersByTimeAsync(600)
    expect(document.activeElement).toBe(wrapper.get('[data-home-choice="understand"]').element)
    await wrapper.get('[data-home-choice="agents"]').trigger('click')
    await vi.advanceTimersByTimeAsync(600)
    await wrapper.get('[data-home-choice="agent-skill"]').trigger('click')
    await vi.advanceTimersByTimeAsync(600)
    expect(document.activeElement).toBe(wrapper.get('.home-guide-step-heading').element)
    await wrapper.get('[data-home-back="topics"]').trigger('click')
    await vi.advanceTimersByTimeAsync(600)
    expect(document.activeElement).toBe(wrapper.get('[data-home-choice="agent-skill"]').element)
  })
})
