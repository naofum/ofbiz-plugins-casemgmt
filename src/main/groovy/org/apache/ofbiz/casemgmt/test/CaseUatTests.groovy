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
 * Phase 2 P2-9 automated UAT for the additional case types.
 */
class CaseUatTests extends OFBizTestCase {

    private static final List<String> OPEN_TASK_STATUSES = [
            'TASK_CREATED', 'TASK_ASSIGNED', 'TASK_IN_PROGRESS', 'TASK_WAITING'
    ]

    CaseUatTests(String name) {
        super(name)
    }

    private GenericValue system() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private GenericValue customerUser() {
        return from('UserLogin').where('userLoginId', 'TEST_CUST_USER').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private Map createCase(String caseTypeId, String ownerPartyId = null) {
        Map result = dispatcher.runSync('createCase', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                customerPartyId : 'TEST_CASEMGMT_CUST',
                ownerPartyId    : ownerPartyId,
                description     : "$caseTypeId UAT case"
        ])
        ok(result)
        return result
    }

    private GenericValue firstOpenTask(String caseId, Long stageSeq = null) {
        return from('CaseTask').where('caseId', caseId).queryList()
                .findAll { GenericValue task ->
                    OPEN_TASK_STATUSES.contains(task.statusId) &&
                            (stageSeq == null || task.stageSeq == stageSeq)
                }
                .sort { GenericValue left, GenericValue right ->
                    (left.stageSeq as long) <=> (right.stageSeq as long) ?:
                            ((left.stepSeq as long) <=> (right.stepSeq as long))
                }
                .find()
    }

    private Map completeTask(GenericValue task, String outcomeEnumId = 'CASE_OUT_COMPLETE') {
        return dispatcher.runSync('completeCaseTask', [
                userLogin    : system(),
                caseTaskId   : task.caseTaskId,
                outcomeEnumId: outcomeEnumId
        ])
    }

    private String caseStatus(String caseId) {
        return from('WorkEffort').where('workEffortId', caseId).queryOne()?.currentStatusId
    }

    private void completeUntilResolved(String caseId) {
        int guard = 0
        while (caseStatus(caseId) != 'CASE_RESOLVED' && guard++ < 10) {
            GenericValue task = firstOpenTask(caseId)
            if (task == null) {
                break
            }
            ok(completeTask(task))
        }
        assert caseStatus(caseId) == 'CASE_RESOLVED'
    }

    private GenericValue slaInstance(String caseId, String milestoneTypeEnumId) {
        return from('CaseSlaInstance').where('caseId', caseId).queryList()
                .find { GenericValue instance -> instance.milestoneTypeEnumId == milestoneTypeEnumId }
    }

    void testCustomerComplaintUat() {
        String caseId = createCase('CUSTOMER_COMPLAINT', 'TEST_AGENT1').caseId
        GenericValue complaintTask = firstOpenTask(caseId)
        assert complaintTask
        assert complaintTask.assignedQueueId == 'CASE_QUEUE_COMPLAINT'
        assert complaintTask.stepName == 'Acknowledge complaint'

        GenericValue firstResponse = slaInstance(caseId, 'SLA_FIRST_RESPONSE')
        assert firstResponse
        assert firstResponse.completedDate == null
        assert slaInstance(caseId, 'SLA_RESOLUTION')

        Map formResult = dispatcher.runSync('getCaseForm', [userLogin: system(), caseId: caseId])
        ok(formResult)
        assert formResult.fields.size() == 2
        Map saveResult = dispatcher.runSync('saveCaseForm', [
                userLogin: system(),
                caseId   : caseId,
                stageSeq : 1,
                stepSeq  : 1,
                values   : [description: 'Damaged delivery and missing refund', priority: '2']
        ])
        ok(saveResult)
        assert saveResult.saved == 2

        Map firstTaskResult = completeTask(complaintTask)
        ok(firstTaskResult)
        assert (firstTaskResult.currentStageSeq as double) == 2.0
        GenericValue completedFirstResponse = slaInstance(caseId, 'SLA_FIRST_RESPONSE')
        assert completedFirstResponse.completedDate
        assert completedFirstResponse.slaStateEnumId == 'SLA_COMPLETED'

        completeUntilResolved(caseId)
        Map processMap = dispatcher.runSync('getCaseProcessMap', [userLogin: system(), caseId: caseId])
        ok(processMap)
        assert processMap.stages.size() == 4
        assert processMap.steps.size() == 4

        Map closeResult = dispatcher.runSync('closeCase', [
                userLogin    : system(),
                caseId       : caseId,
                resolutionId : 'COMPLAINT_RESOLVED'
        ])
        ok(closeResult)
        assert caseStatus(caseId) == 'CASE_CLOSED'

        GenericValue resolutionSla = slaInstance(caseId, 'SLA_RESOLUTION')
        assert resolutionSla.completedDate

        Map metrics = dispatcher.runSync('getCaseMetrics', [
                userLogin : system(),
                caseTypeId: 'CUSTOMER_COMPLAINT'
        ])
        ok(metrics)
        assert metrics.summary.totalCases >= 1
        assert metrics.summary.closedCases >= 1
        assert metrics.summary.slaCompleted >= 2
        assert metrics.slaByMilestone.any { Map row -> row.milestoneName == 'First response' }
        assert metrics.slaByMilestone.any { Map row -> row.milestoneName == 'Resolution' }
    }

    void testServiceRequestApprovalReworkAndServiceStep() {
        String caseId = createCase('SERVICE_REQUEST').caseId
        GenericValue requestTask = firstOpenTask(caseId)
        assert requestTask
        assert requestTask.assignedQueueId == 'CASE_Q_SVC_REQUEST'

        Map requestResult = completeTask(requestTask)
        ok(requestResult)
        assert (requestResult.currentStageSeq as double) == 2.0
        assert (requestResult.currentStageVisitSeq as double) == 1.0

        GenericValue approvalTask = firstOpenTask(caseId, 2L)
        assert approvalTask
        assert approvalTask.stepName == 'Approve request'

        Map rejectResult = completeTask(approvalTask, 'CASE_OUT_REJECT')
        ok(rejectResult)
        assert (rejectResult.currentStageSeq as double) == 1.0
        assert (rejectResult.currentStageVisitSeq as double) == 2.0
        GenericValue reworkTask = firstOpenTask(caseId, 1L)
        assert reworkTask
        assert (reworkTask.stageVisitSeq as long) == 2L

        ok(completeTask(reworkTask))
        GenericValue secondApproval = firstOpenTask(caseId, 2L)
        assert secondApproval
        assert (secondApproval.stageVisitSeq as long) == 2L
        Map approveResult = completeTask(secondApproval, 'CASE_OUT_APPROVE')
        ok(approveResult)
        assert (approveResult.currentStageSeq as double) == 4.0

        GenericValue serviceTask = from('CaseTask')
                .where('caseId', caseId, 'stageSeq', 3L)
                .queryList()
                .find()
        assert serviceTask
        assert serviceTask.statusId == 'TASK_COMPLETED'
        assert serviceTask.taskTypeEnumId == 'CASE_STEP_SERVICE'
        List<GenericValue> serviceEvents = from('CaseEvent')
                .where('caseId', caseId, 'eventTypeEnumId', 'CASE_SERVICE_DONE')
                .queryList()
        assert serviceEvents.size() == 1

        GenericValue completionTask = firstOpenTask(caseId, 4L)
        assert completionTask
        assert completionTask.stepName == 'Confirm completion'
        ok(completeTask(completionTask))
        assert caseStatus(caseId) == 'CASE_RESOLVED'

        Map media = dispatcher.runSync('getCaseMetrics', [
                userLogin : system(),
                caseTypeId: 'SERVICE_REQUEST'
        ])
        ok(media)
        assert media.summary.totalCases >= 1
        assert media.summary.resolvedCases >= 1
    }

    void testCustomerAccessAndUatVisibility() {
        String caseId = createCase('CUSTOMER_COMPLAINT', 'TEST_AGENT1').caseId
        GenericValue customer = customerUser()
        assert customer

        Map customerView = dispatcher.runSync('getCase', [
                userLogin: customer,
                caseId   : caseId
        ])
        ok(customerView)
        assert customerView.permissions.view
        assert !customerView.permissions.update

        GenericValue task = firstOpenTask(caseId)
        Map customerComplete = dispatcher.runSync('completeCaseTask', [
                userLogin    : customer,
                caseTaskId   : task.caseTaskId,
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        assert ServiceUtil.isError(customerComplete)

        Map explanation = dispatcher.runSync('explainCaseState', [
                userLogin: system(),
                caseId   : caseId
        ])
        ok(explanation)
        assert explanation.stageName == 'Intake'
        assert explanation.stepName == 'Acknowledge complaint'

        Map complaintSimulation = dispatcher.runSync('simulateCaseFlow', [
                userLogin : system(),
                caseTypeId: 'CUSTOMER_COMPLAINT',
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        ok(complaintSimulation)
        assert complaintSimulation.resolved
        assert complaintSimulation.path.size() == 4

        Map serviceSimulation = dispatcher.runSync('simulateCaseFlow', [
                userLogin : system(),
                caseTypeId: 'SERVICE_REQUEST',
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        ok(serviceSimulation)
        assert serviceSimulation.resolved
        assert serviceSimulation.path.size() == 4
    }
}
