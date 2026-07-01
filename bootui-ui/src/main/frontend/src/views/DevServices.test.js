import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import DevServices from './DevServices.vue'

function report(overrides = {}) {
  return {
    services: [],
    total: 0,
    snapshotTimestamp: 1_700_000_000_000,
    dockerComposePresent: false,
    testcontainersPresent: false,
    warnings: [],
    ...overrides
  }
}

function service(overrides = {}) {
  return {
    id: 'postgres',
    name: 'PostgreSQL',
    type: 'Database',
    status: 'RUNNING',
    image: 'postgres:16',
    host: 'localhost',
    ports: [],
    source: 'Quarkus Dev Services',
    note: '',
    connectionDetails: {},
    logsAvailable: false,
    restartable: false,
    ...overrides
  }
}

async function mountWith(devServices, {platform} = {}) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(devServices), {status: 200})))
  )

  const global = platform ? {provide: {panels: ref({platform})}} : {}
  const wrapper = mount(DevServices, {global})
  await flushPromises()
  return wrapper
}

describe('DevServices', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders Spring Boot copy and empty state by default', async () => {
    const wrapper = await mountWith(report())

    expect(wrapper.text()).toContain("Docker Compose services are shown from Spring Boot's startup snapshot")
    expect(wrapper.text()).toContain(
      'No Docker Compose, Testcontainers, or Spring Boot service connection beans were detected.'
    )
    expect(wrapper.text()).not.toContain('Quarkus Dev Services start throwaway containers')
  })

  it('renders Quarkus copy and empty state when the platform is quarkus', async () => {
    const wrapper = await mountWith(report(), {platform: 'quarkus'})

    expect(wrapper.text()).toContain('Quarkus Dev Services start throwaway containers in dev/test')
    expect(wrapper.text()).toContain('No Quarkus Dev Services are running')
    expect(wrapper.text()).not.toContain("Docker Compose services are shown from Spring Boot's startup snapshot")
    expect(wrapper.text()).not.toContain(
      'No Docker Compose, Testcontainers, or Spring Boot service connection beans were detected.'
    )
  })

  it('lists running services with a Quarkus snapshot subtitle', async () => {
    const wrapper = await mountWith(report({services: [service()], total: 1}), {platform: 'quarkus'})

    expect(wrapper.text()).toContain('PostgreSQL')
    expect(wrapper.text()).toContain('1 Dev Service')
    expect(wrapper.text()).not.toContain('No Quarkus Dev Services are running')
  })
})
