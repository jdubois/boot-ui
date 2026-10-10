// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'
import {registerDiagnosticAcknowledgementTests} from '../../../bootui-spring-sample-app/e2e/scenarios/diagnostic-acknowledgements.js'

registerDiagnosticAcknowledgementTests(test, expect, acceptConfirm)
