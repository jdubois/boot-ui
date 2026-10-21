// @ts-check
import {expect, test} from '@playwright/test'
import {registerExceptionNavigationTests} from '../scenarios/exception-navigation.js'

registerExceptionNavigationTests(test, expect, {
  uiBase: '/host/dev-console/',
  apiBase: '/host/internal/bootui-api',
  appBase: '/host'
})
