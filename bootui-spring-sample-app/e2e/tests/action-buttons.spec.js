import {expect, test} from '@playwright/test'
import {registerActionButtonTests} from './action-button-checks.js'

registerActionButtonTests(test, expect)
