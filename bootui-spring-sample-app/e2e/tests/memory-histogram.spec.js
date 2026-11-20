import {expect, test} from './fixtures.js'
import {registerMemoryHistogramTests} from '../scenarios/memory-histogram.js'

registerMemoryHistogramTests(test, expect)
