import * as THREE from 'three'
import {describe, expect, it, vi} from 'vitest'
import {EXPLORER_TYPES} from './explorerModel.js'
import {createExplorerModels, explorerModelFor} from './explorerModels.js'

describe('semantic architectural models', () => {
  it('maps every canonical event, exact bean role, reference and group to a local icon and meaningful form', () => {
    const forms = EXPLORER_TYPES.map((kind) => explorerModelFor({kind}))
    expect(new Set(forms.map((model) => model.form)).size).toBe(9)
    for (const model of forms) expect(model.icon).toContain('<svg')
    expect(explorerModelFor({kind: 'CALL', role: 'CONTROLLER'}).form).toBe('signpost')
    expect(explorerModelFor({kind: 'CALL', role: 'SERVICE'}).icon).not.toBe(
      explorerModelFor({kind: 'CALL', role: 'COMPONENT'}).icon
    )
    expect(explorerModelFor({kind: 'CALL', role: 'REPOSITORY'}).form).toBe('archive')
    expect(explorerModelFor({kind: 'SQL_REFERENCE'}).name).toBe('SQL reference')
    expect(explorerModelFor({kind: 'GROUP'}).form).toBe('cluster')
    expect(explorerModelFor({kind: 'FUTURE'}).form).toBe('beacon')
  })
  it('builds a multi-part family, shares geometry/materials, and disposes each resource once without fetching', () => {
    const fetch = vi.spyOn(globalThis, 'fetch')
    const palette = Object.fromEntries(
      ['body', 'trim', 'inlay', 'blue', 'indigo', 'info', 'neutral', 'selected'].map((key) => [
        key,
        new THREE.Color('#668899')
      ])
    )
    const kit = createExplorerModels(palette)
    const nodes = [
      ...EXPLORER_TYPES.map((kind) => ({kind})),
      ...['CONTROLLER', 'SERVICE', 'COMPONENT', 'REPOSITORY'].map((role) => ({kind: 'CALL', role})),
      {kind: 'SQL_REFERENCE'},
      {kind: 'GROUP'},
      {kind: 'FUTURE'}
    ]
    const models = nodes.map((node) => kit.create(node))
    const copies = nodes.map((node) => kit.create(node))
    for (let i = 0; i < models.length; i++) {
      expect(models[i].children.length).toBeGreaterThan(4)
      expect(models[i].userData.halo.visible).toBe(false)
      models[i].children.forEach((mesh, j) => {
        expect(mesh.geometry).toBe(copies[i].children[j].geometry)
        expect(mesh.material).toBe(copies[i].children[j].material)
      })
    }
    const resources = new Set(
      models.flatMap((model) => model.children.flatMap((mesh) => [mesh.geometry, mesh.material]))
    )
    const disposeSpies = [...resources].map((resource) => vi.spyOn(resource, 'dispose'))
    kit.recolor({...palette, selected: new THREE.Color('#34d068')})
    expect(models[0].userData.halo.material.color.getHexString()).toBe('34d068')
    kit.dispose()
    kit.dispose()
    for (const spy of disposeSpies) expect(spy).toHaveBeenCalledOnce()
    expect(fetch).not.toHaveBeenCalled()
  })
})
