import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import SpringSecurity from './SpringSecurity.vue'

function report() {
  return {
    springSecurityPresent: true,
    chains: [
      {
        order: 1,
        requestMatcher: '/api/**',
        requestMatcherType: 'PathPatternParserServerWebExchangeMatcher',
        filters: ['CsrfWebFilter', 'SecurityContextServerWebExchangeWebFilter'],
        csrfEnabled: true,
        corsEnabled: false,
        sessionManagementPresent: true
      }
    ],
    auth: {
      authenticationProviderTypes: ['example.ApplicationAuthenticationManager'],
      userDetailsServiceTypes: [],
      configuredUsername: null
    }
  }
}

async function mountWithPlatform(platform) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            url.toString().includes('/endpoints')
              ? {springSecurityPresent: true, handlerMappingAvailable: true, total: 0, endpoints: []}
              : report()
          ),
          {status: 200}
        )
      )
    )
  )
  const wrapper = mount(SpringSecurity, {
    global: {provide: {panels: ref({platform})}}
  })
  await flushPromises()
  return wrapper
}

describe('SpringSecurity platform copy', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('explains reactive chains and reduced-fidelity matching on WebFlux', async () => {
    const wrapper = await mountWithPlatform('spring-boot-reactive')
    const text = wrapper.text()

    expect(wrapper.get('[data-testid="reactive-fidelity-note"]').text()).toContain('SecurityWebFilterChain')
    expect(text).toContain('WebFilter chains')
    expect(text).toContain('2 WebFilters')
    expect(text).toContain('Security context')
    expect(text).toContain('Reactive authentication managers')
    expect(text).toContain('Annotation-based Spring WebFlux mappings')
    expect(text).toContain('sanitized path-and-method-only exchange')
    expect(text).toContain("reactive chain's public matcher")
    expect(text).not.toContain('Per-endpoint authorization rule resolved by matching each Spring MVC mapping')
  })

  it('preserves servlet terminology by default', async () => {
    const wrapper = await mountWithPlatform('spring-boot')
    const text = wrapper.text()

    expect(wrapper.find('[data-testid="reactive-fidelity-note"]').exists()).toBe(false)
    expect(text).toContain('Filter chains')
    expect(text).toContain('2 filters')
    expect(text).toContain('Session')
    expect(text).toContain('Authentication providers')
    expect(text).toContain('Per-endpoint authorization rule resolved by matching each Spring MVC mapping')
    expect(text).not.toContain('WebFilter chains')
  })
})
