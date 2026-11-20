// @ts-check
import {expect, test} from './fixtures.js'
import {registerGitHubRefreshTests} from '../scenarios/github-refresh.js'

registerGitHubRefreshTests(test, expect)
