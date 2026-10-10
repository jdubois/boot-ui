// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'
import {registerDiagnosticAcknowledgementTests} from '../scenarios/diagnostic-acknowledgements.js'

registerDiagnosticAcknowledgementTests(test, expect, acceptConfirm)
