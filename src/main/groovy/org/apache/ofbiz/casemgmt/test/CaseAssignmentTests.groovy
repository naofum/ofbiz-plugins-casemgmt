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
 * Phase 2 queued assignment tests.
 */
class CaseAssignmentTests extends OFBizTestCase {

    CaseAssignmentTests(String name) {
        super(name)
    }

    private GenericValue system() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String createCase(String caseTypeId) {
        Map result = dispatcher.runSync('createCase', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                customerPartyId : 'TEST_CASEMGMT_CUST',
                description     : 'Assignment test case'
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

    void testSpecificAssignment() {
        String caseId = createCase('TEST_ASSIGN_SPEC')
        GenericValue task = firstTask(caseId)
        assert task
        assert task.assignedPartyId == 'TEST_AGENT1'
    }

    void testQueuePickAndClaim() {
        String caseId = createCase('TEST_ASSIGN_PICK')
        GenericValue task = firstTask(caseId)
        assert task
        assert task.assignedPartyId == null
        assert task.assignedQueueId == 'TEST_QUEUE'

        Map result = dispatcher.runSync('claimCaseTask', [
                userLogin : system(),
                caseTaskId: task.caseTaskId
        ])
        ok(result)
        GenericValue claimed = from('CaseTask').where('caseTaskId', task.caseTaskId).queryOne()
        assert claimed.assignedPartyId == 'system'
        assert claimed.statusId == 'TASK_ASSIGNED'
    }

    void testRoundRobinAssignsDifferentAgents() {
        String firstCaseId = createCase('TEST_ASSIGN_RR')
        String secondCaseId = createCase('TEST_ASSIGN_RR')
        String firstAgent = firstTask(firstCaseId).assignedPartyId
        String secondAgent = firstTask(secondCaseId).assignedPartyId
        assert firstAgent
        assert secondAgent
        assert firstAgent != secondAgent
        assert ['TEST_AGENT1', 'TEST_AGENT2'].contains(firstAgent)
        assert ['TEST_AGENT1', 'TEST_AGENT2'].contains(secondAgent)
    }

    void testLeastLoadAssignsDifferentAgents() {
        String firstCaseId = createCase('TEST_ASSIGN_LOAD')
        String secondCaseId = createCase('TEST_ASSIGN_LOAD')
        String firstAgent = firstTask(firstCaseId).assignedPartyId
        String secondAgent = firstTask(secondCaseId).assignedPartyId
        assert firstAgent
        assert secondAgent
        assert firstAgent != secondAgent
    }

    void testReassignAndRelease() {
        String caseId = createCase('TEST_ASSIGN_SPEC')
        GenericValue task = firstTask(caseId)
        assert task.assignedPartyId == 'TEST_AGENT1'

        Map reassign = dispatcher.runSync('reassignCaseTask', [
                userLogin : system(),
                caseTaskId: task.caseTaskId,
                partyId   : 'TEST_AGENT2',
                reason    : 'test'
        ])
        ok(reassign)
        assert from('CaseTask').where('caseTaskId', task.caseTaskId).queryOne().assignedPartyId == 'TEST_AGENT2'

        Map release = dispatcher.runSync('releaseCaseTask', [
                userLogin : system(),
                caseTaskId: task.caseTaskId
        ])
        ok(release)
        GenericValue released = from('CaseTask').where('caseTaskId', task.caseTaskId).queryOne()
        assert released.assignedPartyId == null
        assert released.statusId == 'TASK_CREATED'
    }

    void testSkillAssignment() {
        String caseId = createCase('TEST_ASSIGN_SKILL')
        GenericValue task = firstTask(caseId)
        assert task
        assert task.assignedPartyId == 'TEST_AGENT1'
    }

    void testQueueWorkload() {
        createCase('TEST_ASSIGN_PICK')
        Map result = dispatcher.runSync('getQueueWorkload', [
                userLogin: system(),
                queueId  : 'TEST_QUEUE'
        ])
        ok(result)
        assert result.members.size() == 2
        assert result.members.any { Map member -> member.partyId == 'TEST_AGENT1' }
        assert result.members.any { Map member -> member.partyId == 'TEST_AGENT2' }
    }
}
