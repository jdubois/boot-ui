import {expect, test} from '@playwright/test'
import {registerMysqlTests} from '../../../bootui-spring-sample-app/e2e/scenarios/mysql.js'

registerMysqlTests(test, expect)
