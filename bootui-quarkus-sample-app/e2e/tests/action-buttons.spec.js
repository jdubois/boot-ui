import {expect, test} from '@playwright/test'
import {registerActionButtonTests} from '../../../bootui-spring-sample-app/e2e/tests/action-button-checks.js'

registerActionButtonTests(test, expect)
