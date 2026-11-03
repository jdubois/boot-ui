{
  const scenarioTabs = [...document.querySelectorAll('[role="tab"]')]
  function selectScenarioTab(tab, focus = false) {
    const navigation = tab.closest('nav')
    const keepNavigationVisible = window.scrollY > 0 && navigation.getBoundingClientRect().top <= 0
    scenarioTabs.forEach((candidate) => {
      const selected = candidate === tab
      candidate.setAttribute('aria-selected', String(selected))
      candidate.tabIndex = selected ? 0 : -1
      document.getElementById(candidate.getAttribute('aria-controls')).hidden = !selected
    })
    if (focus) {
      tab.focus()
      tab.scrollIntoView({block: 'nearest', inline: 'nearest'})
    }
    if (keepNavigationVisible) navigation.scrollIntoView({block: 'start'})
  }

  function restoreScenarioTab() {
    const target = document.getElementById(location.hash.slice(1))
    const panel = target?.closest('[role="tabpanel"]')
    selectScenarioTab(scenarioTabs.find((tab) => tab.getAttribute('aria-controls') === panel?.id) || scenarioTabs[0])
  }

  scenarioTabs.forEach((tab, index) => {
    tab.addEventListener('click', () => {
      selectScenarioTab(tab)
      history.replaceState(null, '', `#${tab.getAttribute('aria-controls')}`)
    })
    tab.addEventListener('keydown', (event) => {
      const next =
        event.key === 'ArrowRight'
          ? (index + 1) % scenarioTabs.length
          : event.key === 'ArrowLeft'
            ? (index - 1 + scenarioTabs.length) % scenarioTabs.length
            : event.key === 'Home'
              ? 0
              : event.key === 'End'
                ? scenarioTabs.length - 1
                : null
      if (next === null) return
      event.preventDefault()
      selectScenarioTab(scenarioTabs[next], true)
      history.replaceState(null, '', `#${scenarioTabs[next].getAttribute('aria-controls')}`)
    })
  })
  window.addEventListener('hashchange', restoreScenarioTab)
  restoreScenarioTab()
}
