import {expect, test} from '@playwright/test'
import {registerStaleAssetTests} from '../scenarios/stale-assets.js'

registerStaleAssetTests(test, expect, {uiPath: '/host/dev-console', apiPath: '/host/internal/bootui-api'})
