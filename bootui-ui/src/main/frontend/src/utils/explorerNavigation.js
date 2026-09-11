const stable = (a, b) => a.stage - b.stage || a.z - b.z || (a.x || 0) - (b.x || 0) || a.id.localeCompare(b.id)

/** Stage order for left/right; depth lanes for up/down, including independent and grouped nodes. */
export function navigateExplorer(nodes, selectedId, key) {
  if (!nodes.length || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(key)) return null
  const ordered = [...nodes].sort(stable)
  const current = ordered.find((node) => node.id === selectedId || node.rowIds.includes(selectedId))
  if (!current) return ordered[0]
  const forward = key === 'ArrowRight' || key === 'ArrowDown'
  if (key === 'ArrowLeft' || key === 'ArrowRight') {
    const stages = [...new Set(ordered.map((node) => node.stage))]
    const stage = stages[stages.indexOf(current.stage) + (forward ? 1 : -1)]
    return (
      ordered
        .filter((node) => node.stage === stage)
        .sort((a, b) => Math.abs(a.z - current.z) - Math.abs(b.z - current.z) || stable(a, b))[0] || current
    )
  }
  const lane = ordered.filter((node) => node.stage === current.stage)
  return lane[lane.indexOf(current) + (forward ? 1 : -1)] || current
}

/** Native Tab/Escape and edited fields are intentionally not camera shortcuts. */
export function explorerKeyAction(event) {
  if (event.altKey || event.ctrlKey || event.metaKey) return null
  if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key))
    return {type: event.shiftKey ? 'orbit' : 'browse', key: event.key}
  if (['+', '=', '-', '_'].includes(event.key))
    return {type: 'zoom', factor: ['+', '='].includes(event.key) ? 0.86 : 1 / 0.86}
  if (event.key === 'Home' && !event.shiftKey) return {type: 'reset'}
  if (['Enter', ' '].includes(event.key) && !event.shiftKey) return {type: 'activate'}
  return null
}
