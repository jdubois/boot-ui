import {expect, test} from '@playwright/test'
import {registerMysqlTests} from '../scenarios/mysql.js'

registerMysqlTests(test, expect)
