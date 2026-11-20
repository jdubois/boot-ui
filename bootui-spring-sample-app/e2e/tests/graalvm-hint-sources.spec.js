// @ts-check
import {expect, test} from '@playwright/test'
import {registerGraalVmHintSourceTests} from '../scenarios/graalvm-hint-sources.js'

registerGraalVmHintSourceTests(test, expect)
