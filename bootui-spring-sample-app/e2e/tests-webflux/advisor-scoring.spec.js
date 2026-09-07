// @ts-check
import {expect, test} from '@playwright/test'
import {registerAdvisorScoringTests} from '../scenarios/advisor-scoring.js'

registerAdvisorScoringTests(test, expect)
