// Spring PetClinic traffic, as in the first run (petclinic-traffic.py), with a fixed iteration count instead of a
// duration: owner search (found, all, none), owner details, vets as HTML and JSON, the error page, static resources,
// and owner, pet, and visit forms, each submitted invalid then valid. The first run sent about 8,700 requests in 130 s.
//
//   node validation/traffic/petclinic.mjs --base-url http://localhost:18181 [--iterations 300] [--summary file.json]

import {Traffic, options, sleep} from './lib.mjs'

const opts = options({baseUrl: 'http://localhost:18181', iterations: 300, pauseMs: 150})
const traffic = new Traffic('petclinic', opts)
const http = traffic.client()
const day = (offset) => new Date(Date.UTC(2026, 0, 1) + offset * 86_400_000).toISOString().slice(0, 10)
// Visit dates around the day the traffic runs: PetClinic rejects a visit in the past.
const today = new Date().toISOString().slice(0, 10)
const future = new Date(Date.now() + 10 * 86_400_000).toISOString().slice(0, 10)
const past = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10)

for (let i = 1; i <= opts.iterations; i++) {
  const ownerId = (i % 10) + 1
  const detailOwner = ((i + 2) % 10) + 1
  await http.get('/', {label: 'GET /', expect: [200]})
  await http.get('/resources/css/petclinic.css', {label: 'GET /**', expect: [200]})
  await http.get('/resources/images/pets.png', {label: 'GET /**', expect: [200]})
  await http.get('/owners/find', {label: 'GET /owners/find', expect: [200]})
  await http.get('/owners?lastName=Davis', {label: 'GET /owners', expect: [200]})
  await http.get('/owners?lastName=', {label: 'GET /owners', expect: [200]})
  await http.get(`/owners?lastName=NoSuch${1000 + i}`, {label: 'GET /owners', expect: [200]})
  await http.get(`/owners/${ownerId}`, {label: 'GET /owners/{ownerId}', expect: [200]})
  await http.get(`/owners/${detailOwner}`, {label: 'GET /owners/{ownerId}', expect: [200]})
  await http.get('/vets.html', {label: 'GET /vets.html', expect: [200]})
  await http.get('/vets', {label: 'GET /vets', headers: {Accept: 'application/json'}, expect: [200]})
  await http.get('/oups', {label: 'GET /oups', expect: [500]})

  await http.get('/owners/new', {label: 'GET /owners/new', expect: [200]})
  const invalidOwner = {firstName: '', lastName: '', address: '', city: '', telephone: 'bad-phone'}
  await http.post('/owners/new', {label: 'POST /owners/new', form: invalidOwner, expect: [200]})
  const created = await http.post('/owners/new', {
    label: 'POST /owners/new',
    form: {
      firstName: `V${i}`,
      lastName: `Validator${i}`,
      address: `${100 + i} Runtime Way`,
      city: 'Madison',
      telephone: `60855${String(i % 100000).padStart(5, '0')}`
    },
    expect: [302]
  })
  const owner = /\/owners\/(\d+)/.exec(created.location || '')?.[1]
  if (owner) {
    await http.get(`/owners/${owner}/edit`, {label: 'GET /owners/{ownerId}/edit', expect: [200]})
    await http.post(`/owners/${owner}/edit`, {label: 'POST /owners/{ownerId}/edit', form: invalidOwner, expect: [200]})
    await http.post(`/owners/${owner}/edit`, {
      label: 'POST /owners/{ownerId}/edit',
      form: {
        firstName: `V${i}`,
        lastName: `Validator${i}Edited`,
        address: `${200 + i} Runtime Way`,
        city: 'Monona',
        telephone: `60955${String(i % 100000).padStart(5, '0')}`
      },
      expect: [302]
    })
    await http.get(`/owners/${owner}/pets/new`, {label: 'GET /owners/{ownerId}/pets/new', expect: [200]})
    await http.post(`/owners/${owner}/pets/new`, {
      label: 'POST /owners/{ownerId}/pets/new',
      form: {name: '', birthDate: '2999-01-01', type: 'cat'},
      expect: [200]
    })
    const petName = `RuntimePet${i}`
    await http.post(`/owners/${owner}/pets/new`, {
      label: 'POST /owners/{ownerId}/pets/new',
      form: {name: petName, birthDate: day(-2000), type: 'dog'},
      expect: [302]
    })
    const details = await http.get(`/owners/${owner}`, {label: 'GET /owners/{ownerId}', expect: [200]})
    const pets = [...details.text.matchAll(new RegExp(`${owner}/pets/(\\d+)/(?:edit|visits/new)`, 'g'))]
    const pet = pets.length ? Math.max(...pets.map((m) => Number(m[1]))) : null
    if (pet) {
      await http.get(`/owners/${owner}/pets/${pet}/edit`, {
        label: 'GET /owners/{ownerId}/pets/{petId}/edit',
        expect: [200]
      })
      await http.post(`/owners/${owner}/pets/${pet}/edit`, {
        label: 'POST /owners/{ownerId}/pets/{petId}/edit',
        form: {name: '', birthDate: '2999-01-01', type: 'dog'},
        expect: [200]
      })
      await http.post(`/owners/${owner}/pets/${pet}/edit`, {
        label: 'POST /owners/{ownerId}/pets/{petId}/edit',
        form: {name: `${petName}E`, birthDate: day(-1500), type: 'lizard'},
        expect: [302]
      })
      const visits = `/owners/${owner}/pets/${pet}/visits/new`
      await http.get(visits, {label: 'GET /owners/{ownerId}/pets/{petId}/visits/new', expect: [200]})
      await http.post(visits, {
        label: 'POST /owners/{ownerId}/pets/{petId}/visits/new',
        form: {date: past, description: 'past invalid', petId: String(pet)},
        expect: [200]
      })
      await http.post(visits, {
        label: 'POST /owners/{ownerId}/pets/{petId}/visits/new',
        form: {date: future > today ? future : today, description: `checkup ${i}`, petId: String(pet)},
        expect: [302]
      })
    }
  }
  await sleep(opts.pauseMs)
}

traffic.finish()
