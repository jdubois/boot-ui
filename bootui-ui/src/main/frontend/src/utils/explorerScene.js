import * as THREE from 'three'
import {OrbitControls} from 'three/addons/controls/OrbitControls.js'
import {STAGES} from './explorerModel.js'
import {MAX_CONCURRENT_PULSES} from './serviceMap.js'
import {createExplorerModels, explorerModelFor} from './explorerModels.js'
import {explorerKeyAction, navigateExplorer} from './explorerNavigation.js'

export const MAX_PIXEL_RATIO = 1.75
const HOME_DIRECTION = new THREE.Vector3(0.08, 0.85, 1).normalize()
const corners = (box) =>
  [box.min.x, box.max.x].flatMap((x) =>
    [box.min.y, box.max.y].flatMap((y) => [box.min.z, box.max.z].map((z) => new THREE.Vector3(x, y, z)))
  )
const overlaps = (a, b) => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

/**
 * Three is imported only by the async scene component, never by the shell or the evidence model.
 * @param {HTMLElement} host
 * @param {{onSelect?: Function, onNavigate?: Function, onFailure?: Function, rendererFactory?: Function}} options
 */
export function createExplorerScene(host, {onSelect, onNavigate, onFailure, rendererFactory} = {}) {
  const canvas = document.createElement('canvas')
  canvas.setAttribute('aria-hidden', 'true')
  canvas.dataset.explorerCanvas = ''
  canvas.style.cssText = 'display:block;width:100%;height:100%;touch-action:none'
  const context = rendererFactory ? null : canvas.getContext('webgl2', {alpha: true, antialias: true})
  if (!rendererFactory && !context)
    throw new Error('WebGL2 is unavailable. Use the execution tree to inspect the same evidence.')
  const renderer = rendererFactory
    ? rendererFactory(canvas)
    : new THREE.WebGLRenderer({canvas, context, antialias: true, alpha: true})
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, MAX_PIXEL_RATIO))
  renderer.setClearColor(0x000000, 0)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  host.appendChild(canvas)
  const labels = document.createElement('div')
  labels.className = 'explorer-scene-labels'
  labels.setAttribute('aria-hidden', 'true')
  host.appendChild(labels)
  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(38, 1, 0.1, 500)
  const controls = new OrbitControls(camera, canvas)
  controls.enableDamping = false
  controls.enablePan = false
  controls.minDistance = 18
  controls.maxDistance = 150
  controls.minPolarAngle = 0.35
  controls.maxPolarAngle = 1.2
  controls.minAzimuthAngle = -0.5
  controls.maxAzimuthAngle = 0.5
  const content = new THREE.Group(),
    pulseGroup = new THREE.Group()
  scene.add(content, pulseGroup)
  scene.add(new THREE.HemisphereLight(0xffffff, 0x637587, 2))
  const keyLight = new THREE.DirectionalLight(0xffffff, 2.4)
  keyLight.position.set(-10, 20, 15)
  scene.add(keyLight)
  const geometries = {
    particle: new THREE.SphereGeometry(0.14, 10, 8),
    ring: new THREE.TorusGeometry(0.65, 0.045, 6, 32)
  }
  let disposed = false,
    raf = null,
    width = 1,
    height = 1,
    effects = [],
    layout = {nodes: [], edges: []}
  let activeRows = new Set(),
    selectedId = null,
    cursorId = null
  let meshes = new Map(),
    curves = new Map(),
    modelBounds = [],
    labelEntries = [],
    dynamicMaterials = [],
    edgeGeometries = []
  let palette,
    resetDistance = 40
  const frameTarget = new THREE.Vector3()
  const raycaster = new THREE.Raycaster(),
    pointer = new THREE.Vector2()
  let pointerStart = null

  function tokens() {
    const style = getComputedStyle(host)
    const color = (name, fallback) => {
      const value = style.getPropertyValue(name).trim()
      return new THREE.Color(value || fallback)
    }
    const surface = color('--bootui-surface-solid', '#ffffff')
    const neutral = color('--bootui-text-subtle', '#5b6b80')
    return {
      neutral: color('--bootui-text-subtle', '#5b6b80'),
      body: surface.clone().lerp(neutral, 0.16),
      trim: surface.clone().lerp(neutral, 0.43),
      inlay: color('--bootui-text', '#152033'),
      info: color('--bootui-info-text', '#087990'),
      indigo: new THREE.Color('#6654ad'),
      line: color('--bootui-text-subtle', '#5b6b80'),
      selected: color('--bootui-green', '#198754'),
      blue: color('--bootui-blue', '#0d6efd'),
      failed: color('--bootui-danger-text', '#b02a37'),
      slow: color('--bootui-warning-text-strong', '#6f5300')
    }
  }
  palette = tokens()
  const modelKit = createExplorerModels(palette)
  const pulseMaterials = {}
  for (const tone of ['ok', 'slow', 'failed'])
    pulseMaterials[tone] = new THREE.MeshBasicMaterial({color: tone === 'ok' ? palette.blue : palette[tone]})
  function material(color, line = false) {
    const value = line
      ? new THREE.LineBasicMaterial({color, transparent: true, opacity: 0.5})
      : new THREE.MeshStandardMaterial({color, roughness: 0.68, metalness: 0.08})
    dynamicMaterials.push(value)
    return value
  }
  function requestRender() {
    if (!disposed && raf == null && document.visibilityState !== 'hidden') raf = requestAnimationFrame(render)
  }
  function reset() {
    controls.target.copy(frameTarget)
    camera.position.copy(HOME_DIRECTION.clone().multiplyScalar(resetDistance).add(frameTarget))
    controls.update()
    requestRender()
  }
  function recolor() {
    palette = tokens()
    modelKit.recolor(palette)
    for (const [id, mesh] of meshes) {
      const node = mesh.userData.node
      const selected = id === cursorId || node.rowIds.includes(selectedId)
      mesh.userData.halo.visible = selected || node.rowIds.some((row) => activeRows.has(row))
      mesh.userData.halo.scale.setScalar(selected && document.activeElement === host ? 1.12 : 1)
    }
    for (const tone of ['ok', 'slow', 'failed'])
      pulseMaterials[tone].color.copy(tone === 'ok' ? palette.blue : palette[tone])
    for (const child of content.children)
      if (child.isLine) {
        const highlighted = child.userData.rowIds?.some((id) => id === selectedId || activeRows.has(id))
        child.material.color.copy(highlighted ? palette.selected : palette.line)
        child.material.opacity = highlighted ? 1 : 0.5
      }
    requestRender()
  }
  function addLabel(node, point, stage = false) {
    const label = document.createElement(stage ? 'span' : 'button')
    label.className = stage ? 'explorer-stage-label' : 'explorer-node-label'
    const name = node.label || node.kind
    if (stage) label.textContent = name
    else {
      const category = document.createElement('span')
      category.className = 'explorer-node-category'
      category.textContent = explorerModelFor(node).name
      const title = document.createElement('span')
      title.className = 'explorer-node-name'
      title.textContent = name.replace(/([a-z])([A-Z])/g, '$1\u200b$2').replace(/([._/])/g, '$1\u200b')
      label.append(category, title)
      if (node.rowIds.length > 1 || node.status || node.operation || node.context) {
        const meta = document.createElement('span')
        meta.className = 'explorer-node-meta'
        meta.textContent = [
          node.rowIds.length > 1 ? `×${node.rowIds.length}` : '',
          node.context,
          node.status,
          node.operation
        ]
          .filter(Boolean)
          .join(' · ')
        label.appendChild(meta)
      }
    }
    if (!stage) {
      label.setAttribute('type', 'button')
      label.tabIndex = -1
      label.title = `${node.kind}: ${node.label}${node.rowIds.length > 1 ? ` · ${node.rowIds.length} observations` : ''}`
      label.dataset.nodeId = node.id
      label.addEventListener('click', () => {
        host.focus({preventScroll: true})
        choose(node, true)
      })
    }
    const leader = stage ? null : document.createElement('span')
    if (leader) {
      leader.className = 'explorer-label-leader'
      labels.appendChild(leader)
    }
    labels.appendChild(label)
    labelEntries.push({label, leader, point, node, stage})
  }
  function update(next, selection = null, active = []) {
    if (disposed) return
    const first = !layout.nodes.length
    const wasFramed =
      controls.target.distanceTo(frameTarget) < 0.001 &&
      camera.position.distanceTo(HOME_DIRECTION.clone().multiplyScalar(resetDistance).add(frameTarget)) < 0.001
    const previousTarget = frameTarget.clone(),
      previousDistance = resetDistance
    layout = next
    if (selectedId !== selection) cursorId = null
    selectedId = selection
    if (!next.nodes.some((node) => node.id === cursorId)) cursorId = null
    activeRows = new Set(active)
    for (const value of dynamicMaterials) value.dispose()
    for (const value of edgeGeometries) value.dispose()
    dynamicMaterials = []
    edgeGeometries = []
    content.clear()
    labels.replaceChildren()
    meshes.clear()
    curves.clear()
    labelEntries = []
    const positions = new Map(next.nodes.map((node) => [node.id, new THREE.Vector3(node.x, node.y, node.z)]))
    for (const node of next.nodes) {
      const mesh = modelKit.create(node)
      mesh.position.copy(positions.get(node.id))
      mesh.userData.node = node
      meshes.set(node.id, mesh)
      content.add(mesh)
      addLabel(node, mesh.position.clone().add(new THREE.Vector3(0, -1.9, 0.7)))
    }
    content.updateMatrixWorld(true)
    modelBounds = [...meshes.values()].map((mesh) => new THREE.Box3().setFromObject(mesh))
    for (const edge of next.edges) {
      const from = positions.get(edge.fromId),
        to = positions.get(edge.toId)
      if (!from || !to) continue
      const same = from.equals(to)
      const curve = new THREE.CatmullRomCurve3([
        from,
        from.clone().add(new THREE.Vector3(same ? 1.8 : (to.x - from.x) * 0.3, same ? 2 : 0.25, same ? -1 : 0)),
        to.clone().add(new THREE.Vector3(same ? -1.8 : -(to.x - from.x) * 0.3, same ? 2 : 0.25, same ? -1 : 0)),
        to
      ])
      curves.set(edge.id, curve)
      const geometry = new THREE.BufferGeometry().setFromPoints(curve.getPoints(28))
      edgeGeometries.push(geometry)
      const line = new THREE.Line(geometry, material(palette.line, true))
      line.userData.rowIds = edge.rowIds
      content.add(line)
    }
    for (let stage = 0; stage < STAGES.length; stage++) {
      const nodes = next.nodes.filter((node) => node.stage === stage)
      if (!nodes.length) continue
      const z = Math.min(...nodes.map((node) => node.z))
      const x = (Math.min(...nodes.map((node) => node.x)) + Math.max(...nodes.map((node) => node.x))) / 2
      addLabel({label: STAGES[stage]}, new THREE.Vector3(x, 3.5, z), true)
    }
    fitBounds()
    recolor()
    if (first || (wasFramed && (!frameTarget.equals(previousTarget) || resetDistance !== previousDistance))) reset()
    requestRender()
  }
  function fitBounds() {
    if (!layout.nodes.length) return
    const bounds = new THREE.Box3()
    for (const box of modelBounds) bounds.union(box)
    for (const entry of labelEntries) bounds.expandByPoint(entry.point)
    bounds.getCenter(frameTarget)
    const right = new THREE.Vector3().crossVectors(camera.up, HOME_DIRECTION).normalize()
    const up = new THREE.Vector3().crossVectors(HOME_DIRECTION, right)
    const tangent = Math.tan(THREE.MathUtils.degToRad(camera.fov / 2))
    const captionMargin = Math.min(160, width * 0.3)
    const horizontal = tangent * camera.aspect * ((width - captionMargin * 2) / width)
    const vertical = tangent * Math.max(0.3, (height - 170) / height)
    // Fit the nearest as well as the furthest corners: a flat depth estimate clips front-row models.
    resetDistance = Math.max(
      18,
      ...corners(bounds).map((point) => {
        const offset = point.sub(frameTarget)
        return (
          offset.dot(HOME_DIRECTION) +
          Math.max(Math.abs(offset.dot(right)) / horizontal, Math.abs(offset.dot(up)) / vertical)
        )
      })
    )
    controls.maxDistance = Math.max(150, resetDistance * 2)
    camera.far = Math.max(500, controls.maxDistance + bounds.getSize(new THREE.Vector3()).length())
    camera.updateProjectionMatrix()
  }
  function select(id, active = []) {
    if (disposed) return
    if (selectedId !== id) cursorId = null
    selectedId = id
    activeRows = new Set(active)
    recolor()
  }
  function choose(node, activate = false) {
    cursorId = node.id
    if (node.kind !== 'GROUP') selectedId = node.rowIds[0]
    onNavigate?.(node)
    if (node.kind !== 'GROUP' || activate) onSelect?.(node)
    camera.updateMatrixWorld()
    const point = meshes.get(node.id)?.position
    const projected = point?.clone().project(camera)
    if (projected && (Math.abs(projected.x) > 0.8 || Math.abs(projected.y) > 0.7)) {
      const delta = point.clone().sub(controls.target)
      controls.target.add(delta)
      camera.position.add(delta)
      controls.update()
    }
    recolor()
  }
  function keydown(event) {
    if (disposed || event.target !== host) return
    if (event.key === 'Escape') {
      host.blur()
      return
    }
    const action = explorerKeyAction(event)
    if (!action) return
    event.preventDefault()
    if (action.type === 'browse') {
      const node = navigateExplorer(layout.nodes, cursorId || selectedId, action.key)
      if (node) choose(node)
    } else if (action.type === 'activate') {
      const node = layout.nodes.find((value) => value.id === cursorId || value.rowIds.includes(selectedId))
      if (node) choose(node, true)
    } else if (action.type === 'reset') reset()
    else {
      const offset = camera.position.clone().sub(controls.target)
      const spherical = new THREE.Spherical().setFromVector3(offset)
      if (action.type === 'zoom')
        spherical.radius = THREE.MathUtils.clamp(
          spherical.radius * action.factor,
          controls.minDistance,
          controls.maxDistance
        )
      else {
        if (action.key === 'ArrowLeft') spherical.theta -= 0.12
        if (action.key === 'ArrowRight') spherical.theta += 0.12
        if (action.key === 'ArrowUp') spherical.phi -= 0.1
        if (action.key === 'ArrowDown') spherical.phi += 0.1
        spherical.theta = THREE.MathUtils.clamp(spherical.theta, controls.minAzimuthAngle, controls.maxAzimuthAngle)
        spherical.phi = THREE.MathUtils.clamp(spherical.phi, controls.minPolarAngle, controls.maxPolarAngle)
      }
      camera.position.copy(new THREE.Vector3().setFromSpherical(spherical).add(controls.target))
      controls.update()
      requestRender()
    }
  }
  function setEffects(next) {
    effects = next.slice(0, MAX_CONCURRENT_PULSES)
    requestRender()
  }
  function pulseAt(effect, progress) {
    const curve = curves.get(effect.edgeId)
    const from = meshes.get(effect.fromId)?.position,
      to = meshes.get(effect.toId)?.position
    if (!to) return null
    if (!curve || effect.static || ['evict', 'clear'].includes(effect.mode)) return to
    let t = progress
    if (effect.mode === 'lookup-return') t = progress < 0.5 ? progress * 2 : (1 - progress) * 2
    if (['propagate', 'inbound'].includes(effect.mode)) t = 1 - progress
    return curve.getPoint(Math.max(0, Math.min(1, t))) || from
  }
  function render(now) {
    raf = null
    if (disposed || document.visibilityState === 'hidden') return
    pulseGroup.clear()
    let animating = false
    for (const effect of effects) {
      const progress = effect.static ? 1 : Math.min(1, (now - effect.startedAt) / effect.durationMs)
      const point = pulseAt(effect, progress)
      if (!point) continue
      animating ||= !effect.static && progress < 1
      const isRing = effect.static || ['evict', 'clear', 'write', 'failure'].includes(effect.mode)
      const particle = new THREE.Mesh(
        isRing ? geometries.ring : geometries.particle,
        pulseMaterials[effect.tone] || pulseMaterials.ok
      )
      particle.position.copy(point)
      if (isRing) {
        particle.rotation.x = -Math.PI / 2
        particle.scale.setScalar(
          effect.static ? 1.3 : effect.mode === 'write' ? 0.8 + progress * 0.5 : 1.5 - progress * 0.7
        )
      }
      pulseGroup.add(particle)
      if (effect.mode === 'clear') {
        const outline = new THREE.Mesh(geometries.ring, pulseMaterials[effect.tone] || pulseMaterials.ok)
        outline.position.copy(point)
        outline.rotation.x = -Math.PI / 2
        outline.scale.setScalar(1.9 - progress * 0.5)
        pulseGroup.add(outline)
      }
      if (effect.tone === 'failed') {
        const ring = new THREE.Mesh(geometries.ring, pulseMaterials.failed)
        ring.position.copy(meshes.get(effect.fromId)?.position || point)
        ring.rotation.x = -Math.PI / 2
        pulseGroup.add(ring)
      }
      if (effect.tone === 'slow' && !effect.static) {
        for (let i = 1; i <= 3; i++) {
          const trail = new THREE.Mesh(geometries.particle, pulseMaterials.slow)
          trail.position.copy(pulseAt(effect, Math.max(0, progress - i * 0.035)))
          trail.scale.setScalar(1 - i * 0.2)
          pulseGroup.add(trail)
        }
      }
    }
    camera.updateMatrixWorld()
    const bottomInset = Math.max(
      58,
      (host.parentElement?.querySelector('.explorer-camera-hint')?.clientHeight || 0) + 8
    )
    const obstacles = modelBounds.map((bounds) => {
      const projected = corners(bounds).map((point) => point.project(camera))
      return {
        left: Math.min(...projected.map((point) => (point.x * 0.5 + 0.5) * width)) - 4,
        right: Math.max(...projected.map((point) => (point.x * 0.5 + 0.5) * width)) + 4,
        top: Math.min(...projected.map((point) => (-point.y * 0.5 + 0.5) * height)) - 4,
        bottom: Math.max(...projected.map((point) => (-point.y * 0.5 + 0.5) * height)) + 4
      }
    })
    const occupied = []
    const prioritized = [...labelEntries].sort(
      (a, b) =>
        Number(Boolean(b.node.id === cursorId || b.node.rowIds?.includes(selectedId))) -
          Number(Boolean(a.node.id === cursorId || a.node.rowIds?.includes(selectedId))) ||
        Number(a.stage) - Number(b.stage)
    )
    for (const {label, leader, point, node, stage} of prioritized) {
      const projected = point.clone().project(camera)
      const selected = !stage && (node.id === cursorId || node.rowIds.includes(selectedId))
      if (!stage) {
        label.dataset.selected = String(selected)
        label.dataset.active = String(node.rowIds.some((id) => activeRows.has(id)))
      }
      label.hidden = false
      const w = label.offsetWidth || 110,
        h = label.offsetHeight || 44
      const anchorX = (projected.x * 0.5 + 0.5) * width
      const anchorY = (-projected.y * 0.5 + 0.5) * height
      let x = anchorX,
        y = anchorY
      let placed = false
      const step = h + 12
      const candidates = stage
        ? [
            [0, 0],
            [0, -step],
            [w + 12, 0],
            [-w - 12, 0]
          ]
        : [
            [0, 0],
            [0, step],
            [w + 12, 0],
            [-w - 12, 0],
            [0, -step],
            [w + 12, step],
            [-w - 12, step],
            [0, step * 2]
          ]
      if (!stage) {
        const freeLanes = []
        for (let left = 8; left + w <= width - 8; left += w + 12) {
          for (let top = 54; top + h <= height - bottomInset - 6; top += step) {
            freeLanes.push([left + w / 2 - anchorX, top + h / 2 - anchorY])
          }
        }
        freeLanes.sort((a, b) => Math.hypot(...a) - Math.hypot(...b))
        candidates.push(...freeLanes)
      }
      // Labels may move sideways as well as vertically, but never cover another label or model.
      for (const [dx, dy] of candidates) {
        x = THREE.MathUtils.clamp(anchorX + dx, w / 2 + 6, width - w / 2 - 6)
        y = anchorY + dy
        const box = {left: x - w / 2 - 4, right: x + w / 2 + 4, top: y - h / 2 - 4, bottom: y + h / 2 + 4}
        const collision =
          occupied.some((other) => overlaps(box, other)) || obstacles.some((other) => overlaps(box, other))
        if (box.top >= 48 && box.bottom <= height - bottomInset && !collision) {
          occupied.push(box)
          placed = true
          break
        }
      }
      label.hidden = projected.z < -1 || projected.z > 1 || Math.abs(projected.x) > 1.15 || !placed
      if (leader) {
        leader.hidden = label.hidden || Math.hypot(x - anchorX, y - anchorY) < 1
        const endX = THREE.MathUtils.clamp(anchorX, x - w / 2, x + w / 2)
        const endY = THREE.MathUtils.clamp(anchorY, y - h / 2, y + h / 2)
        const dx = endX - anchorX,
          dy = endY - anchorY
        leader.style.cssText = `left:${anchorX}px;top:${anchorY}px;height:${Math.hypot(dx, dy)}px;transform-origin:top center;transform:rotate(${Math.atan2(-dx, dy)}rad)`
      }
      label.style.transform = `translate(-50%, -50%) translate(${x}px, ${y}px)`
    }
    try {
      renderer.render(scene, camera)
    } catch {
      fail('The 3D renderer stopped. The execution tree retains all captured evidence.')
      return
    }
    if (animating) requestRender()
  }
  function resize() {
    const previousDistance = resetDistance
    width = Math.max(1, host.clientWidth)
    height = Math.max(1, host.clientHeight)
    renderer.setSize(width, height, false)
    camera.aspect = width / height
    camera.updateProjectionMatrix()
    fitBounds()
    if (layout.nodes.length) {
      camera.position
        .sub(controls.target)
        .multiplyScalar(resetDistance / previousDistance)
        .add(controls.target)
      controls.update()
    }
    requestRender()
  }
  function down(event) {
    host.focus({preventScroll: true})
    pointerStart = [event.clientX, event.clientY]
  }
  function up(event) {
    if (!pointerStart || Math.hypot(event.clientX - pointerStart[0], event.clientY - pointerStart[1]) > 5) return
    const bounds = canvas.getBoundingClientRect()
    pointer.set(
      ((event.clientX - bounds.left) / bounds.width) * 2 - 1,
      -((event.clientY - bounds.top) / bounds.height) * 2 + 1
    )
    raycaster.setFromCamera(pointer, camera)
    const hit = raycaster.intersectObjects([...meshes.values()], true)[0]
    if (hit) choose(hit.object.userData.node, true)
    pointerStart = null
  }
  function visibility() {
    if (document.visibilityState === 'hidden') {
      if (raf != null) cancelAnimationFrame(raf)
      raf = null
      effects = []
    } else requestRender()
  }
  function fail(reason) {
    dispose()
    onFailure?.(reason)
  }
  function lost(event) {
    event.preventDefault()
    fail('The WebGL context was lost. Use the execution tree, or retry 3D.')
  }
  const observer = new ResizeObserver(resize)
  observer.observe(host)
  const themeObserver = new MutationObserver(recolor)
  themeObserver.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-bs-theme', 'data-bootui-theme', 'style', 'class']
  })
  controls.addEventListener('change', requestRender)
  canvas.addEventListener('pointerdown', down)
  canvas.addEventListener('pointerup', up)
  canvas.addEventListener('webglcontextlost', lost)
  host.addEventListener('keydown', keydown)
  host.addEventListener('focus', recolor)
  host.addEventListener('blur', recolor)
  document.addEventListener('visibilitychange', visibility)
  function dispose() {
    if (disposed) return
    disposed = true
    if (raf != null) cancelAnimationFrame(raf)
    raf = null
    effects = []
    observer.disconnect()
    themeObserver.disconnect()
    controls.removeEventListener('change', requestRender)
    controls.dispose()
    canvas.removeEventListener('pointerdown', down)
    canvas.removeEventListener('pointerup', up)
    canvas.removeEventListener('webglcontextlost', lost)
    host.removeEventListener('keydown', keydown)
    host.removeEventListener('focus', recolor)
    host.removeEventListener('blur', recolor)
    document.removeEventListener('visibilitychange', visibility)
    for (const geometry of Object.values(geometries)) geometry.dispose()
    modelKit.dispose()
    for (const geometry of edgeGeometries) geometry.dispose()
    for (const material of [...dynamicMaterials, ...Object.values(pulseMaterials)]) material.dispose()
    scene.clear()
    meshes.clear()
    curves.clear()
    labelEntries = []
    modelBounds = []
    renderer.dispose()
    renderer.forceContextLoss()
    canvas.remove()
    labels.remove()
  }
  resize()
  reset()
  return {update, select, setEffects, reset, dispose}
}
