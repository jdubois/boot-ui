// Whether the Runtime Insights panel can be opened, read from the panel manifest the app shell already loaded. Before
// the manifest arrives, or in a standalone component test, the panel is assumed usable, as every view assumes.
export function insightsUsable(panels) {
  if (!panels?.panels) return true
  const panel = panels.panels.find((candidate) => candidate.id === 'runtime-insights')
  return !!panel && panel.available !== false && panel.enabled !== false
}
