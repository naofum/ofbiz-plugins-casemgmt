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

import java.sql.Timestamp

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Phase 2 process map, explain, stack and simulator tests.
 */
class CaseVisibilityTests extends OFBizTestCase {

    CaseVisibilityTests(String name) {
        super(name)
    }

    private GenericValue system() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private GenericValue user(String userLoginId) {
        return from('UserLogin').where('userLoginId', userLoginId).queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String createCase(String caseTypeId = 'PILOT_INQUIRY') {
        Map result = dispatcher.runSync('createCase', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                customerPartyId : 'TEST_CASEMGMT_CUST',
                description     : "$caseTypeId visibility test case"
        ])
        ok(result)
        return result.caseId
    }

    void testProcessMap() {
        String caseId = createCase()
        Map result = dispatcher.runSync('getCaseProcessMap', [userLogin: system(), caseId: caseId])
        ok(result)
        assert result.stages.size() == 3
        assert result.steps.size() >= 3
        assert result.transitions.size() >= 2
        assert result.sla.size() >= 1
        assert (result.currentStageSeq as double) == 1.0
        assert result.mermaidDefinition
        assert result.mermaidDefinition.contains('flowchart LR')
        assert result.mermaidDefinition.contains('subgraph stage_1')
        assert result.mermaidDefinition.contains('Stage 1:')
        assert result.mermaidDefinition.contains('Step 1:')
        assert result.mermaidDefinition.contains('step_1_1')
        assert result.mermaidDefinition.contains('classDef current')
        assert result.mermaidDefinition.contains('step_1_1 -->|"COMPLETE (default)"|')
        assert result.mermaidDefinition.contains('__CASE_RESOLVED__')
    }

    void testProcessMapShowsReworkTransition() {
        String caseId = createCase('SERVICE_REQUEST')
        Map result = dispatcher.runSync('getCaseProcessMap', [userLogin: system(), caseId: caseId])
        ok(result)
        assert result.mermaidDefinition.contains('step_2_1 -->|"REJECT"| step_1_1')
    }

    void testCaseTypeProcessMap() {
        Map result = dispatcher.runSync('getCaseTypeProcessMap', [
                userLogin : system(),
                caseTypeId: 'PILOT_INQUIRY'
        ])
        ok(result)
        assert result.stages.size() == 3
        assert result.steps.size() >= 3
        assert result.transitions.size() >= 2
        assert result.resolvedDefinitionVersion
        assert result.mermaidDefinition.contains('subgraph stage_1')
        assert result.mermaidDefinition.contains('Step 1:')
        assert result.mermaidDefinition.contains('classDef definition')
        assert result.mermaidDefinition.contains('definition')
        assert !result.mermaidDefinition.contains('step_1_1 current')
        assert result.stageMermaidDefinition.contains('stage_1 --> stage_2')
        assert result.stepMermaidDefinition.contains('Step 1:')
        assert result.stepMermaidDefinition.contains('step_1_1')
        assert result.transitionMermaidDefinition.contains('flowchart LR')
        assert result.transitionMermaidDefinition.contains('COMPLETE')

        Map autoResult = dispatcher.runSync('getCaseTypeProcessMap', [
                userLogin : system(),
                caseTypeId: 'TEST_AUTO'
        ])
        ok(autoResult)
        assert autoResult.stepMermaidDefinition.contains('step_1_1 --> step_1_2')
    }

    void testListCasesAssignedToMeFilter() {
        String assignedCaseId = createCase('TEST_ASSIGN_SPEC')
        String queueCaseId = createCase('TEST_ASSIGN_PICK')
        String customerCaseId = createCase('PILOT_INQUIRY')

        Map assignedOnly = dispatcher.runSync('listCases', [
                userLogin   : user('TEST_AGENT1_USER'),
                assignedToMe: 'Y'
        ])
        ok(assignedOnly)
        List<String> assignedIds = assignedOnly.cases.collect { Map row -> row.caseId }
        assert assignedIds.contains(assignedCaseId)
        assert !assignedIds.contains(queueCaseId)
        assert !assignedIds.contains(customerCaseId)

        Map otherAgent = dispatcher.runSync('listCases', [
                userLogin   : user('TEST_AGENT2_USER'),
                assignedToMe: 'Y'
        ])
        ok(otherAgent)
        assert !otherAgent.cases.any { Map row -> row.caseId == assignedCaseId }

        Map customerAll = dispatcher.runSync('listCases', [
                userLogin: user('TEST_CUST_USER')
        ])
        ok(customerAll)
        assert customerAll.cases.any { Map row -> row.caseId == customerCaseId }

        Map customerAssignedOnly = dispatcher.runSync('listCases', [
                userLogin   : user('TEST_CUST_USER'),
                assignedToMe: 'Y'
        ])
        ok(customerAssignedOnly)
        assert !customerAssignedOnly.cases.any { Map row -> row.caseId == customerCaseId }
    }

    void testListCasesFiltersAndClosedWindow() {
        String openCaseId = createCase()
        String closedCaseId = createCase()
        Map closeResult = dispatcher.runSync('closeCase', [
                userLogin   : system(),
                caseId      : closedCaseId,
                resolutionId: 'TEST_RESOLVED'
        ])
        ok(closeResult)
        GenericValue closedCase = from('Case').where('caseId', closedCaseId).queryOne()
        closedCase.closedDate = new Timestamp(UtilDateTime.nowTimestamp().time - 10L * 24L * 60L * 60L * 1000L)
        closedCase.store()

        Map base = [userLogin: system(), caseTypeId: 'PILOT_INQUIRY']
        Map hidden = dispatcher.runSync('listCases', base + [closedWithin: 'NONE'])
        ok(hidden)
        List<String> hiddenIds = hidden.cases.collect { Map row -> row.caseId }
        assert hiddenIds.contains(openCaseId)
        assert !hiddenIds.contains(closedCaseId)

        Map week = dispatcher.runSync('listCases', base + [closedWithin: 'WEEK'])
        ok(week)
        assert !week.cases.any { Map row -> row.caseId == closedCaseId }

        Map month = dispatcher.runSync('listCases', base + [closedWithin: 'MONTH'])
        ok(month)
        assert month.cases.any { Map row -> row.caseId == closedCaseId }

        Map year = dispatcher.runSync('listCases', base + [closedWithin: 'YEAR'])
        ok(year)
        assert year.cases.any { Map row -> row.caseId == closedCaseId }

        Map closedOnly = dispatcher.runSync('listCases', base + [
                statusId: 'CASE_CLOSED',
                closedWithin: 'YEAR'
        ])
        ok(closedOnly)
        assert closedOnly.cases.any { Map row -> row.caseId == closedCaseId }

        String closedDay = new java.text.SimpleDateFormat('yyyy-MM-dd').format(closedCase.closedDate)
        Map closedPeriod = dispatcher.runSync('listCases', base + [
                statusId: 'CASE_CLOSED',
                closedPeriod: closedDay
        ])
        ok(closedPeriod)
        assert closedPeriod.cases.any { Map row -> row.caseId == closedCaseId }

        Map openOnly = dispatcher.runSync('listCases', base + [openOnly: 'Y'])
        ok(openOnly)
        assert openOnly.cases.any { Map row -> row.caseId == openCaseId }
        assert !openOnly.cases.any { Map row -> row.caseId == closedCaseId }

        Map ageBucket = dispatcher.runSync('listCases', base + [
                ageBucket: '0-1d',
                openOnly: 'Y'
        ])
        ok(ageBucket)
        assert ageBucket.cases.any { Map row -> row.caseId == openCaseId }
    }

    void testExplainIncludesStageAndStepNames() {
        String caseId = createCase()
        Map result = dispatcher.runSync('explainCaseState', [userLogin: system(), caseId: caseId])
        ok(result)
        assert result.stageName == 'Intake'
        assert result.stepName == 'Triage'
        assert result.permissions.view
        assert result.blockedReasons.size() >= 1
        assert result.nextActions.size() >= 1
    }

    void testCaseStack() {
        createCase()
        Map result = dispatcher.runSync('getCaseStack', [userLogin: system()])
        ok(result)
        assert result.total >= 1
        assert !result.byStage.isEmpty()
        assert !result.bySla.isEmpty()
    }

    void testSimulateFlow() {
        List<GenericValue> before = from('Case').queryList()
        Map result = dispatcher.runSync('simulateCaseFlow', [
                userLogin : system(),
                caseTypeId: 'PILOT_INQUIRY',
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        ok(result)
        assert result.resolved
        assert result.path.size() == 3
        assert result.path[0].stageName == 'Intake'
        assert result.path[2].stageName == 'Resolution'
        List<GenericValue> after = from('Case').queryList()
        assert after.size() == before.size()
    }
}
