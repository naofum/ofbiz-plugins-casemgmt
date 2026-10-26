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

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Phase 2 row-level case access tests.
 */
class CaseAccessTests extends OFBizTestCase {

    CaseAccessTests(String name) {
        super(name)
    }

    private GenericValue user(String userLoginId) {
        return from('UserLogin').where('userLoginId', userLoginId).queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String createCase(String caseTypeId, String ownerPartyId, String customerPartyId = 'TEST_CASEMGMT_CUST') {
        Map result = dispatcher.runSync('createCase', [
                userLogin       : user('system'),
                caseTypeId      : caseTypeId,
                ownerPartyId    : ownerPartyId,
                customerPartyId : customerPartyId,
                description     : 'Access test case'
        ])
        ok(result)
        assert result.caseId
        return result.caseId
    }

    private GenericValue firstTask(String caseId) {
        return from('CaseTask').where('caseId', caseId).queryList().find { GenericValue task ->
            ['TASK_CREATED', 'TASK_ASSIGNED', 'TASK_IN_PROGRESS'].contains(task.statusId)
        }
    }

    void testOwnerCustomerAndRuleAccess() {
        String caseId = createCase('TEST_ACCESS', 'TEST_AGENT1')

        Map adminResult = dispatcher.runSync('getCase', [userLogin: user('system'), caseId: caseId])
        ok(adminResult)
        assert adminResult.permissions.admin

        Map ownerResult = dispatcher.runSync('getCase', [userLogin: user('TEST_AGENT1_USER'), caseId: caseId])
        ok(ownerResult)
        assert ownerResult.permissions.view
        assert ownerResult.permissions.update
        assert ownerResult.permissions.close
        assert !ownerResult.permissions.admin

        Map customerResult = dispatcher.runSync('getCase', [userLogin: user('TEST_CUST_USER'), caseId: caseId])
        ok(customerResult)
        assert customerResult.permissions.view
        assert !customerResult.permissions.update

        Map ruleResult = dispatcher.runSync('getCase', [userLogin: user('TEST_AGENT2_USER'), caseId: caseId])
        ok(ruleResult)
        assert ruleResult.permissions.view
        assert !ruleResult.permissions.update

        Map strangerResult = dispatcher.runSync('getCase', [userLogin: user('TEST_STRANGER_USER'), caseId: caseId])
        assert ServiceUtil.isError(strangerResult)
    }

    void testUpdateAndClosePermissions() {
        String updateCaseId = createCase('TEST_ACCESS', 'TEST_AGENT1')
        GenericValue task = firstTask(updateCaseId)
        assert task

        Map deniedUpdate = dispatcher.runSync('completeCaseTask', [
                userLogin    : user('TEST_CUST_USER'),
                caseTaskId   : task.caseTaskId,
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        assert ServiceUtil.isError(deniedUpdate)

        Map ownerUpdate = dispatcher.runSync('completeCaseTask', [
                userLogin    : user('TEST_AGENT1_USER'),
                caseTaskId   : task.caseTaskId,
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        ok(ownerUpdate)

        String closeCaseId = createCase('TEST_ACCESS', 'TEST_AGENT1')
        Map deniedClose = dispatcher.runSync('closeCase', [
                userLogin: user('TEST_CUST_USER'),
                caseId   : closeCaseId
        ])
        assert ServiceUtil.isError(deniedClose)

        Map ownerClose = dispatcher.runSync('closeCase', [
                userLogin: user('TEST_AGENT1_USER'),
                caseId   : closeCaseId
        ])
        ok(ownerClose)
    }

    void testPermissionServices() {
        String caseId = createCase('TEST_ACCESS', 'TEST_AGENT1')
        Map permissionsResult = dispatcher.runSync('getCasePermissions', [
                userLogin: user('TEST_AGENT1_USER'),
                caseId   : caseId
        ])
        ok(permissionsResult)
        assert permissionsResult.permissions.update

        Map hasUpdate = dispatcher.runSync('hasCasePermission', [
                userLogin       : user('TEST_CUST_USER'),
                caseId          : caseId,
                permissionEnumId: 'CASE_UPDATE'
        ])
        ok(hasUpdate)
        assert hasUpdate.hasPermission == false
    }

    void testListCasesFiltersByAccess() {
        String caseId = createCase('PILOT_INQUIRY', 'TEST_AGENT1')

        Map strangerList = dispatcher.runSync('listCases', [userLogin: user('TEST_STRANGER_USER')])
        ok(strangerList)
        assert !strangerList.cases.any { Map caseMap -> caseMap.caseId == caseId }

        Map ownerList = dispatcher.runSync('listCases', [userLogin: user('TEST_AGENT1_USER')])
        ok(ownerList)
        assert ownerList.cases.any { Map caseMap -> caseMap.caseId == caseId }

        Map adminList = dispatcher.runSync('listCases', [userLogin: user('system')])
        ok(adminList)
        assert adminList.cases.any { Map caseMap -> caseMap.caseId == caseId }
    }
}
