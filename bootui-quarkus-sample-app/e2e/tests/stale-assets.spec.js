import {expect, test} from '@playwright/test'
import {registerStaleAssetTests} from '../../../bootui-spring-sample-app/e2e/scenarios/stale-assets.js'

registerStaleAssetTests(test, expect)
