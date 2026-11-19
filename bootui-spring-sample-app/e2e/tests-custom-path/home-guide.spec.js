// @ts-check
import {expect, test} from '@playwright/test'
import {registerHomeGuideTests} from '../scenarios/home-guide.js'

registerHomeGuideTests(test, expect, {uiPath: '/host/dev-console', apiPath: '/host/internal/bootui-api'})
