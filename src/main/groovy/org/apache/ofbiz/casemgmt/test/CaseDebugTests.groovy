/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ofbiz.casemgmt.test

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Tests for the casemgmt debug logging services.
 */
class CaseDebugTests extends OFBizTestCase {

    CaseDebugTests(String name) {
        super(name)
    }

    private GenericValue system() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String createCase() {
        Map result = dispatcher.runSync('createCase', [
                userLogin : system(),
                caseTypeId: 'PILOT_INQUIRY'
        ])
        ok(result)
        return result.caseId
    }

    void testLogMessage() {
        Map result = dispatcher.runSync('caseDebugLogMessage', [
                userLogin: system(),
                message  : 'casemgmt-debug-message-test',
                level    : 'INFO'
        ])
        ok(result)
        assert result.logged
        assert result.level == 'INFO'
    }

    void testLogServiceParametersAndCaseVariables() {
        String caseId = createCase()
        try {
            delegator.create('CaseVariable', [
                    caseId           : caseId,
                    variableName     : 'visible.value',
                    valueTypeEnumId  : 'CASE_VAR_STRING',
                    valueText        : 'visible-value',
                    sensitiveIndicator: 'N',
                    scopeEnumId      : 'CASE_VAR_SCOPE_CASE',
                    createdDate      : UtilDateTime.nowTimestamp()
            ])
            delegator.create('CaseVariable', [
                    caseId           : caseId,
                    variableName     : 'secret.value',
                    valueTypeEnumId  : 'CASE_VAR_STRING',
                    valueText        : 'secret-value',
                    sensitiveIndicator: 'Y',
                    scopeEnumId      : 'CASE_VAR_SCOPE_CASE',
                    createdDate      : UtilDateTime.nowTimestamp()
            ])
            Map result = dispatcher.runSync('caseDebugLogServiceParameters', [
                    userLogin     : system(),
                    serviceName   : 'debugTarget',
                    caseId        : caseId,
                    inputParameters: [amount: 100, password: 'do-not-log'],
                    outputParameters: [status: 'OK'],
                    variables     : [requestId: 'req-1'],
                    includeSensitive: 'N'
            ])
            ok(result)
            assert result.logged
            assert result.variableCount == 2
            assert result.variables['visible.value'] == 'visible-value'
            assert result.variables['secret.value'] == '***'

            Map visible = dispatcher.runSync('caseDebugLogServiceParameters', [
                    userLogin     : system(),
                    caseId        : caseId,
                    includeSensitive: 'Y'
            ])
            ok(visible)
            assert visible.variables['secret.value'] == 'secret-value'
        } finally {
            from('CaseVariable').where('caseId', caseId).queryList()
                    .each { GenericValue variable -> delegator.removeValue(variable) }
        }
    }

    void testInvokeServiceAndLog() {
        Map result = dispatcher.runSync('caseDebugInvokeService', [
                userLogin       : system(),
                serviceName     : 'casemgmtTestServiceComplete',
                serviceParameters: [:],
                includeSensitive: 'N'
        ])
        ok(result)
        assert result.success
        assert result.result.outcomeEnumId == 'CASE_OUT_COMPLETE'
    }
}
