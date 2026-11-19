// @ts-check
import {expect, test} from '@playwright/test'
import {registerGitHubConsentTests} from '../scenarios/github-consent.js'

registerGitHubConsentTests(test, expect, {uiPath: '/host/dev-console', apiPath: '/host/internal/bootui-api'})
