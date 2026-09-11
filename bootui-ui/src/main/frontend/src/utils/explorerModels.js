import * as THREE from 'three'
import {RoundedBoxGeometry} from 'three/addons/geometries/RoundedBoxGeometry.js'
import {SVGLoader} from 'three/addons/loaders/SVGLoader.js'
import signpost from 'bootstrap-icons/icons/signpost-split.svg?raw'
import gear from 'bootstrap-icons/icons/gear-wide-connected.svg?raw'
import layers from 'bootstrap-icons/icons/layers.svg?raw'
import archive from 'bootstrap-icons/icons/archive.svg?raw'
import terminal from 'bootstrap-icons/icons/terminal.svg?raw'
import database from 'bootstrap-icons/icons/database.svg?raw'
import lightning from 'bootstrap-icons/icons/lightning-charge.svg?raw'
import globe from 'bootstrap-icons/icons/globe2.svg?raw'
import envelope from 'bootstrap-icons/icons/envelope.svg?raw'
import collection from 'bootstrap-icons/icons/collection.svg?raw'
import lock from 'bootstrap-icons/icons/shield-lock.svg?raw'
import clock from 'bootstrap-icons/icons/clock.svg?raw'
import breaker from 'bootstrap-icons/icons/bezier2.svg?raw'
import alert from 'bootstrap-icons/icons/exclamation-triangle.svg?raw'
import boxes from 'bootstrap-icons/icons/boxes.svg?raw'
import hexagon from 'bootstrap-icons/icons/hexagon.svg?raw'

const MODELS = {
  REQUEST: {name: 'HTTP request', form: 'gateway', icon: globe, accent: 'blue'},
  CONTROLLER: {name: 'Controller', form: 'signpost', icon: signpost, accent: 'blue'},
  SERVICE: {name: 'Service', form: 'layers', icon: gear, accent: 'indigo'},
  COMPONENT: {name: 'Component', form: 'layers', icon: layers, accent: 'neutral'},
  REPOSITORY: {name: 'Repository', form: 'archive', icon: archive, accent: 'indigo'},
  SQL: {name: 'SQL statement', form: 'terminal', icon: terminal, accent: 'info'},
  SQL_REFERENCE: {name: 'SQL reference', form: 'database', icon: database, accent: 'info'},
  CACHE: {name: 'Cache', form: 'chips', icon: lightning, accent: 'info'},
  REST_CLIENT: {name: 'HTTP client', form: 'gateway', icon: globe, accent: 'indigo'},
  MAIL: {name: 'Mail', form: 'envelope', icon: envelope, accent: 'blue'},
  MESSAGING: {name: 'Messaging', form: 'queue', icon: collection, accent: 'indigo'},
  SECURITY: {name: 'Security', form: 'shield', icon: lock, accent: 'info'},
  SCHEDULED: {name: 'Scheduled work', form: 'clock', icon: clock, accent: 'blue'},
  FAULT_TOLERANCE: {name: 'Fault tolerance', form: 'breaker', icon: breaker, accent: 'indigo'},
  EXCEPTION: {name: 'Exception', form: 'alert', icon: alert, accent: 'neutral'},
  GROUP: {name: 'Grouped observations', form: 'cluster', icon: boxes, accent: 'neutral'},
  EVENT: {name: 'Activity', form: 'beacon', icon: hexagon, accent: 'neutral'}
}

/** Presentation is categorical, never a health assessment or a guessed bean role. */
export function explorerModelFor(node) {
  const role = node.role || ({1: 'CONTROLLER', 2: 'COMPONENT', 3: 'REPOSITORY'}[node.stage] ?? 'COMPONENT')
  const key = node.kind === 'CALL' ? role : node.kind
  return MODELS[key] || MODELS.EVENT
}

function silhouette(points) {
  const shape = new THREE.Shape()
  points.forEach(([x, y], index) => (index ? shape.lineTo(x, y) : shape.moveTo(x, y)))
  shape.closePath()
  const geometry = new THREE.ExtrudeGeometry(shape, {
    depth: 0.38,
    bevelEnabled: true,
    bevelSegments: 2,
    steps: 1,
    bevelSize: 0.075,
    bevelThickness: 0.075,
    curveSegments: 8
  })
  geometry.translate(0, 0, -0.19)
  return geometry
}

/**
 * An authored kit of beveled housings, inlays and local vector emblems. Every instance shares GPU
 * resources; SVGLoader only parses compile-time Bootstrap assets, never event text or remote SVGs.
 */
export function createExplorerModels(palette) {
  const geometries = {
    box: new RoundedBoxGeometry(1, 1, 1, 2, 0.1),
    cylinder: new THREE.CylinderGeometry(1, 1, 1, 32),
    ring: new THREE.TorusGeometry(1.52, 0.045, 6, 48),
    sphere: new THREE.SphereGeometry(1, 16, 12),
    shield: silhouette([
      [-0.94, 0.75],
      [0, 1.05],
      [0.94, 0.75],
      [0.78, -0.35],
      [0, -1],
      [-0.78, -0.35]
    ]),
    triangle: silhouette([
      [0, 1.05],
      [1.05, -0.82],
      [-1.05, -0.82]
    ]),
    envelope: silhouette([
      [-1.1, -0.65],
      [1.1, -0.65],
      [1.1, 0.45],
      [0, 1],
      [-1.1, 0.45]
    ])
  }
  const materials = {}
  const icons = new Map()
  let disposed = false
  for (const key of ['body', 'trim', 'inlay', 'blue', 'indigo', 'info', 'neutral', 'selected']) {
    materials[key] = new THREE.MeshStandardMaterial({
      color: palette[key],
      roughness: key === 'trim' ? 0.38 : 0.62,
      metalness: key === 'trim' ? 0.22 : 0.08
    })
  }
  materials.emblem = new THREE.MeshBasicMaterial({color: 0xffffff, side: THREE.DoubleSide})
  materials.halo = new THREE.MeshBasicMaterial({color: palette.selected})
  for (const [accent, color] of Object.entries({
    blue: '#0a53be',
    indigo: '#6654ad',
    info: '#087990',
    neutral: '#56667b'
  }))
    materials[`badge-${accent}`] = new THREE.MeshBasicMaterial({color})

  function emblem(svg) {
    if (!icons.has(svg)) {
      const paths = new SVGLoader().parse(svg.replaceAll('currentColor', '#ffffff')).paths
      const shapes = paths.flatMap((path) => SVGLoader.createShapes(path))
      const geometry = new THREE.ShapeGeometry(shapes, 8)
      geometry.translate(-8, -8, 0)
      geometry.scale(1 / 16, -1 / 16, 1)
      icons.set(svg, geometry)
    }
    return icons.get(svg)
  }
  function create(node) {
    const spec = explorerModelFor(node)
    const root = new THREE.Group()
    root.userData.node = node
    root.userData.model = spec.form
    const part = (shape, material, scale, position = [0, 0, 0]) => {
      const mesh = new THREE.Mesh(geometries[shape], materials[material])
      mesh.scale.set(...scale)
      mesh.position.set(...position)
      mesh.userData.node = node
      root.add(mesh)
      return mesh
    }
    const box = (scale, position, material = 'body') => part('box', material, scale, position)
    const badge = (x = 0, y = 0.15, z = 0.64, size = 1.05) => {
      box([size + 0.22, size + 0.22, 0.12], [x, y, z], `badge-${spec.accent}`)
      const mesh = new THREE.Mesh(emblem(spec.icon), materials.emblem)
      mesh.scale.setScalar(size)
      mesh.position.set(x, y, z + 0.07)
      mesh.userData.node = node
      root.add(mesh)
    }
    // The shared low plinth anchors the family without obscuring each model's silhouette.
    box([2.75, 0.16, 2.05], [0, -1.03, 0], 'trim')
    const halo = part('ring', 'halo', [1, 1, 1], [0, -0.91, 0])
    halo.rotation.x = -Math.PI / 2
    halo.visible = false
    root.userData.halo = halo
    switch (spec.form) {
      case 'gateway':
        box([0.32, 1.9, 0.7], [-1, -0.02, 0])
        box([0.32, 1.9, 0.7], [1, -0.02, 0])
        box([2.32, 0.35, 0.7], [0, 0.86, 0])
        box([1.65, 1.28, 0.2], [0, -0.08, -0.12], 'inlay')
        badge(0, -0.02, 0.12, 0.92)
        for (let i = 0; i < 3; i++) box([0.13, 0.08, 0.1], [-0.5 + i * 0.22, 0.87, 0.38], spec.accent)
        break
      case 'signpost':
        box([0.17, 1.85, 0.2], [-0.68, -0.04, 0])
        box([0.17, 1.85, 0.2], [0.68, -0.04, 0])
        box([2.25, 1.32, 0.6], [0, 0.3, 0])
        box([1.7, 0.14, 0.7], [0.18, -0.51, 0], spec.accent)
        badge(0, 0.3, 0.36, 0.86)
        break
      case 'layers':
        for (let i = 0; i < 3; i++) {
          const layer = box([2.12, 0.43, 1.55], [0, -0.6 + i * 0.62, 0], i === 1 ? spec.accent : 'body')
          layer.rotation.y = (i - 1) * 0.12
        }
        badge(0, 0.18, 0.88, 0.92)
        break
      case 'archive':
        box([2.08, 1.9, 1.3], [0, 0, -0.06])
        for (let i = 0; i < 3; i++) {
          box([1.76, 0.44, 0.14], [0, -0.6 + i * 0.58, 0.65], i === 2 ? spec.accent : 'trim')
          box([0.48, 0.07, 0.09], [0, -0.57 + i * 0.58, 0.76], 'inlay')
        }
        badge(0, 0.63, 0.82, 0.57)
        break
      case 'terminal':
        box([0.34, 0.65, 0.35], [0, -0.62, 0])
        box([2.3, 1.62, 0.48], [0, 0.23, 0])
        box([2.03, 1.32, 0.09], [0, 0.23, 0.28], 'inlay')
        badge(0, 0.23, 0.35, 0.94)
        box([1.7, 0.12, 0.72], [0, -0.88, 0.4], 'trim')
        break
      case 'database':
        for (let i = 0; i < 3; i++) {
          part('cylinder', i === 2 ? spec.accent : 'body', [1.04, 0.48, 0.83], [0, -0.58 + i * 0.57, 0])
          box([0.62, 0.07, 0.1], [0, -0.58 + i * 0.57, 0.84], 'trim')
        }
        badge(0, 0.27, 0.91, 0.64)
        break
      case 'chips':
        for (let i = 0; i < 3; i++) {
          box([1.9, 0.31, 1.5], [0, -0.65 + i * 0.55, 0], i === 2 ? spec.accent : 'body')
          for (const side of [-1, 1])
            for (let pin = 0; pin < 3; pin++)
              box([0.32, 0.1, 0.13], [side * 1.02, -0.65 + i * 0.55, -0.48 + pin * 0.48], 'trim')
        }
        badge(0, 0.2, 0.82, 0.85)
        break
      case 'envelope':
        part('envelope', 'body', [1, 1, 1], [0, 0, 0])
        badge(0, 0.05, 0.31, 1.02)
        break
      case 'queue':
        box([2.5, 0.23, 1.55], [0, -0.65, 0], spec.accent)
        for (let i = 0; i < 3; i++) box([0.62, 1.04 + i * 0.18, 1.04], [-0.81 + i * 0.81, 0.05 + i * 0.09, 0])
        badge(0, 0.07, 0.59, 0.7)
        break
      case 'shield':
        part('shield', spec.accent, [1.03, 1.03, 1.5])
        badge(0, 0.16, 0.42, 0.93)
        break
      case 'clock': {
        const clock = part('cylinder', 'body', [1.02, 0.52, 1.02], [0, 0.03, 0])
        clock.rotation.x = Math.PI / 2
        badge(0, 0.03, 0.33, 1.1)
        box([0.18, 0.26, 0.25], [0, 1.1, 0], spec.accent)
        break
      }
      case 'breaker': {
        box([2.1, 0.48, 1.3], [0, -0.65, 0])
        for (const side of [-1, 1]) box([0.3, 0.82, 0.5], [side * 0.72, -0.11, 0], 'trim')
        const arm = box([1.55, 0.2, 0.28], [-0.02, 0.63, 0], spec.accent)
        arm.rotation.z = 0.4
        badge(0, -0.37, 0.72, 0.68)
        break
      }
      case 'alert':
        part('triangle', 'body', [1, 1, 1.5])
        badge(0, -0.07, 0.42, 0.82)
        break
      case 'cluster':
        for (let i = 0; i < 3; i++)
          box([0.75, 1.15 + i * 0.28, 0.9], [-0.85 + i * 0.85, -0.27 + i * 0.14, (i % 2) * -0.35])
        badge(0, 0.15, 0.55, 0.73)
        break
      default:
        part('sphere', 'body', [0.95, 0.95, 0.75])
        badge(0, 0.08, 0.7, 0.88)
    }
    return root
  }
  function recolor(next) {
    for (const [key, material] of Object.entries(materials))
      if (key !== 'emblem' && !key.startsWith('badge-')) material.color.copy(next[key === 'halo' ? 'selected' : key])
  }
  function dispose() {
    if (disposed) return
    disposed = true
    for (const geometry of [...Object.values(geometries), ...icons.values()]) geometry.dispose()
    for (const material of Object.values(materials)) material.dispose()
    icons.clear()
  }
  return {create, recolor, dispose}
}
