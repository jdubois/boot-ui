import {expect, test} from '@playwright/test'
import {registerStaleAssetTests} from '../scenarios/stale-assets.js'

registerStaleAssetTests(test, expect)
