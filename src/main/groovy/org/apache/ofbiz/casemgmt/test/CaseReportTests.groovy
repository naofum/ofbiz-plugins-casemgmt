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
 * Phase 2 report tests.
 */
class CaseReportTests extends OFBizTestCase {

    private static final List<String> OPEN_TASK_STATUSES = [
            'TASK_CREATED', 'TASK_ASSIGNED', 'TASK_IN_PROGRESS', 'TASK_WAITING'
    ]

    CaseReportTests(String name) {
        super(name)
    }

    private GenericValue system() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String createCase(String caseTypeId = 'TEST_SLA') {
        Map result = dispatcher.runSync('createCase', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                customerPartyId : 'TEST_CASEMGMT_CUST',
                description     : 'Report test case'
        ])
        ok(result)
        return result.caseId
    }

    private String caseStatus(String caseId) {
        return from('WorkEffort').where('workEffortId', caseId).queryOne()?.currentStatusId
    }

    private void resolveCase(String caseId) {
        int guard = 0
        while (caseStatus(caseId) != 'CASE_RESOLVED' && guard++ < 10) {
            GenericValue task = from('CaseTask').where('caseId', caseId).queryList()
                    .find { GenericValue value -> OPEN_TASK_STATUSES.contains(value.statusId) }
            if (task == null) {
                break
            }
            Map result = dispatcher.runSync('completeCaseTask', [
                    userLogin    : system(),
                    caseTaskId   : task.caseTaskId,
                    outcomeEnumId: 'CASE_OUT_COMPLETE'
            ])
            ok(result)
        }
        assert caseStatus(caseId) == 'CASE_RESOLVED'
    }

    void testOpenCaseMetrics() {
        String caseId = createCase()
        Map result = dispatcher.runSync('getCaseMetrics', [
                userLogin : system(),
                caseTypeId: 'TEST_SLA'
        ])
        ok(result)
        assert result.summary.totalCases >= 1
        assert result.summary.openCases >= 1
        assert result.summary.backlogCases >= 1
        assert result.summary.slaTotal >= 1
        assert result.summary.averageOpenAgeMinutes >= 0
        assert result.byCaseType.any { Map row -> row.caseTypeId == 'TEST_SLA' }
        assert result.aging.any { Map row -> row.bucket == '0-1d' && row.count >= 1 }
        assert result.stack.total >= 1
        assert result.stack.byStage.any { Map row -> row.stageSeq == '1' }
    }

    void testResolvedAndClosedCaseMetrics() {
        String caseId = createCase()
        resolveCase(caseId)
        Map closeResult = dispatcher.runSync('closeCase', [
                userLogin    : system(),
                caseId       : caseId,
                resolutionId : 'TEST_RESOLVED'
        ])
        ok(closeResult)

        Map result = dispatcher.runSync('getCaseMetrics', [
                userLogin : system(),
                caseTypeId: 'TEST_SLA'
        ])
        ok(result)
        assert result.summary.totalCases >= 1
        assert result.summary.closedCases >= 1
        assert result.summary.averageCycleMinutes >= 0
        assert result.summary.slaCompleted >= 1
        assert result.summary.slaOnTime >= 1
        assert result.summary.slaCompliancePct >= 0
        assert result.byStage.any { Map row -> row.completedCount >= 1 }
        assert result.slaByMilestone.any { Map row -> row.milestoneName == 'Resolution' }
        assert !result.throughputDaily.isEmpty()
        assert result.throughputDaily.any { Map row -> row.count >= 1 }
    }

    void testCsvExport() {
        createCase()
        Map result = dispatcher.runSync('exportCaseMetrics', [
                userLogin : system(),
                caseTypeId: 'TEST_SLA'
        ])
        ok(result)
        assert result.csv
        assert result.csv.contains('Summary')
        assert result.csv.contains('Cycle time by case type')
        assert result.csv.contains('SLA compliance by milestone')
        assert result.csv.contains('TEST_SLA')
    }

    void testCaseTypeFilter() {
        createCase('TEST_SLA')
        createCase('PILOT_INQUIRY')
        Map result = dispatcher.runSync('getCaseMetrics', [
                userLogin : system(),
                caseTypeId: 'TEST_SLA',
                stageSeq  : '1'
        ])
        ok(result)
        assert result.summary.totalCases >= 2
        assert result.byCaseType.size() == 1
        assert result.byCaseType[0].caseTypeId == 'TEST_SLA'
    }
}
