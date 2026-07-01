import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'
import {ref} from 'vue'

import HttpProbe from './HttpProbe.vue'

describe('HttpProbe', () => {
  it('describes the Spring Boot app in the subtitle by default', () => {
    const wrapper = mount(HttpProbe)
    expect(wrapper.text()).toContain('running Spring Boot app')
  })

  it('describes the Quarkus app when the platform is quarkus', () => {
    const wrapper = mount(HttpProbe, {
      global: {provide: {panels: ref({platform: 'quarkus'})}}
    })
    expect(wrapper.text()).toContain('running Quarkus app')
    expect(wrapper.text()).not.toContain('Spring Boot')
  })
})
