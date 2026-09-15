import {expect, test} from '@playwright/test'
import {registerMysqlTests} from '../scenarios/mysql.js'

registerMysqlTests(test, expect, {uiPath: '/host/dev-console', apiPath: '/host/internal/bootui-api'})
