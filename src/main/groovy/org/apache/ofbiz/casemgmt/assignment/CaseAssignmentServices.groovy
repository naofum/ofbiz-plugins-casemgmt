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
package org.apache.ofbiz.casemgmt.assignment

import java.sql.Timestamp

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.casemgmt.access.CaseAccessHelper
import org.apache.ofbiz.casemgmt.cases.CaseConcurrencyHelper
import org.apache.ofbiz.casemgmt.cases.CaseRuntimeHelper
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

Map assignCaseTask() {
    String caseTaskId = parameters.caseTaskId
    GenericValue task = EntityQuery.use(delegator).from('CaseTask').where('caseTaskId', caseTaskId).queryOne()
    if (task == null) {
        return error("Case task not found: $caseTaskId")
    }
    if (['TASK_COMPLETED', 'TASK_CANCELLED', 'TASK_SKIPPED'].contains(task.statusId)) {
        return error("Case task is already closed: $caseTaskId")
    }
    GenericValue accessCase = EntityQuery.use(delegator).from('Case').where('caseId', task.caseId).queryOne()
    if (accessCase == null) {
        return error("Case not found: ${task.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, accessCase, 'CASE_ASSIGN')) {
        return error(CaseAccessHelper.permissionError(userLogin, accessCase, 'CASE_ASSIGN'))
    }
    if (!CaseConcurrencyHelper.lockCase(delegator, task.caseId)) {
        return error("Case was updated by another operation; reload and retry")
    }

    String policyId = parameters.assignmentPolicyId
    if (!policyId) {
        GenericValue stepDef = EntityQuery.use(delegator)
                .from('CaseStepDef')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : from('Case').where('caseId', task.caseId).queryOne()?.caseTypeId,
                        definitionVersion: task.definitionVersion,
                        stageSeq         : task.stageSeq,
                        stepSeq          : task.stepSeq
                ]))
                .queryOne()
        policyId = stepDef?.assignmentPolicyId
    }

    Timestamp now = UtilDateTime.nowTimestamp()
    String loginId = userLogin?.userLoginId ?: 'system'
    Map assignment = CaseAssignmentHelper.resolveAssignment(delegator, policyId, task.assignedPartyId, now)
    String partyId = assignment.assignedPartyId
    String queueId = assignment.assignedQueueId
    task.assignedPartyId = partyId
    task.assignedQueueId = queueId
    task.statusId = partyId ? 'TASK_ASSIGNED' : 'TASK_CREATED'
    task.store()
    syncWorkEffortAssignment(delegator, task, partyId, now)
    CaseRuntimeHelper.createCaseEvent(delegator, task.caseId, 'CASE_TASK_ASSIGNED', loginId, now, [
            fromStageSeq : task.stageSeq,
            stageVisitSeq: task.stageVisitSeq,
            fromStepSeq  : task.stepSeq,
            detail       : "Task assigned to ${partyId ?: queueId ?: 'unassigned'}"
    ])
    return success([
            caseTaskId     : task.caseTaskId,
            assignedPartyId: partyId,
            assignedQueueId: queueId,
            statusId       : task.statusId
    ])
}

Map claimCaseTask() {
    String caseTaskId = parameters.caseTaskId
    GenericValue task = EntityQuery.use(delegator).from('CaseTask').where('caseTaskId', caseTaskId).queryOne()
    if (task == null) {
        return error("Case task not found: $caseTaskId")
    }
    if (['TASK_COMPLETED', 'TASK_CANCELLED', 'TASK_SKIPPED'].contains(task.statusId)) {
        return error("Case task is already closed: $caseTaskId")
    }
    GenericValue accessCase = EntityQuery.use(delegator).from('Case').where('caseId', task.caseId).queryOne()
    if (accessCase == null) {
        return error("Case not found: ${task.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, accessCase, 'CASE_UPDATE')) {
        return error(CaseAccessHelper.permissionError(userLogin, accessCase, 'CASE_UPDATE'))
    }
    if (!CaseConcurrencyHelper.lockCase(delegator, task.caseId)) {
        return error("Case was updated by another operation; reload and retry")
    }
    String partyId = userLogin?.partyId
    if (!partyId) {
        return error("A party is required to claim a case task")
    }
    if (task.assignedPartyId && task.assignedPartyId != partyId) {
        return error("Case task is already assigned to ${task.assignedPartyId}")
    }
    if (task.assignedPartyId == partyId && task.statusId == 'TASK_ASSIGNED') {
        return success([caseTaskId: task.caseTaskId, assignedPartyId: partyId,
                assignedQueueId: task.assignedQueueId, statusId: task.statusId])
    }

    Timestamp now = UtilDateTime.nowTimestamp()
    String loginId = userLogin?.userLoginId ?: 'system'
    if (!CaseConcurrencyHelper.claimTask(delegator, task.caseTaskId, task.statusId, [
            assignedPartyId: partyId,
            statusId       : 'TASK_ASSIGNED'
    ])) {
        return error("Case task was already claimed or processed: $caseTaskId")
    }
    task.assignedPartyId = partyId
    task.statusId = 'TASK_ASSIGNED'
    syncWorkEffortAssignment(delegator, task, partyId, now)
    CaseRuntimeHelper.createCaseEvent(delegator, task.caseId, 'CASE_TASK_ASSIGNED', loginId, now, [
            fromStageSeq : task.stageSeq,
            stageVisitSeq: task.stageVisitSeq,
            fromStepSeq  : task.stepSeq,
            detail       : "Task claimed by $partyId"
    ])
    return success([
            caseTaskId     : task.caseTaskId,
            assignedPartyId: partyId,
            assignedQueueId: task.assignedQueueId,
            statusId       : task.statusId
    ])
}

Map reassignCaseTask() {
    String caseTaskId = parameters.caseTaskId
    String partyId = parameters.partyId
    GenericValue task = EntityQuery.use(delegator).from('CaseTask').where('caseTaskId', caseTaskId).queryOne()
    if (task == null) {
        return error("Case task not found: $caseTaskId")
    }
    if (!partyId) {
        return error("partyId is required")
    }
    GenericValue accessCase = EntityQuery.use(delegator).from('Case').where('caseId', task.caseId).queryOne()
    if (accessCase == null) {
        return error("Case not found: ${task.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, accessCase, 'CASE_ASSIGN')) {
        return error(CaseAccessHelper.permissionError(userLogin, accessCase, 'CASE_ASSIGN'))
    }
    if (!CaseConcurrencyHelper.lockCase(delegator, task.caseId)) {
        return error("Case was updated by another operation; reload and retry")
    }
    Timestamp now = UtilDateTime.nowTimestamp()
    String loginId = userLogin?.userLoginId ?: 'system'
    if (!CaseConcurrencyHelper.updateTask(delegator, task.caseTaskId, task.statusId, [
            assignedPartyId: partyId,
            statusId       : 'TASK_ASSIGNED'
    ])) {
        return error("Case task was already processed: $caseTaskId")
    }
    task.assignedPartyId = partyId
    task.statusId = 'TASK_ASSIGNED'
    syncWorkEffortAssignment(delegator, task, partyId, now)
    CaseRuntimeHelper.createCaseEvent(delegator, task.caseId, 'CASE_TASK_ASSIGNED', loginId, now, [
            fromStageSeq : task.stageSeq,
            stageVisitSeq: task.stageVisitSeq,
            fromStepSeq  : task.stepSeq,
            detail       : "Task reassigned to $partyId: ${parameters.reason ?: ''}"
    ])
    return success([caseTaskId: task.caseTaskId, assignedPartyId: partyId, statusId: task.statusId])
}

Map releaseCaseTask() {
    String caseTaskId = parameters.caseTaskId
    GenericValue task = EntityQuery.use(delegator).from('CaseTask').where('caseTaskId', caseTaskId).queryOne()
    if (task == null) {
        return error("Case task not found: $caseTaskId")
    }
    GenericValue accessCase = EntityQuery.use(delegator).from('Case').where('caseId', task.caseId).queryOne()
    if (accessCase == null) {
        return error("Case not found: ${task.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, accessCase, 'CASE_UPDATE')) {
        return error(CaseAccessHelper.permissionError(userLogin, accessCase, 'CASE_UPDATE'))
    }
    if (!CaseConcurrencyHelper.lockCase(delegator, task.caseId)) {
        return error("Case was updated by another operation; reload and retry")
    }
    Timestamp now = UtilDateTime.nowTimestamp()
    String loginId = userLogin?.userLoginId ?: 'system'
    if (!CaseConcurrencyHelper.releaseTask(delegator, task.caseTaskId, task.statusId,
            task.assignedPartyId, [assignedPartyId: null, statusId: 'TASK_CREATED'])) {
        return error("Case task was already released or processed: $caseTaskId")
    }
    task.assignedPartyId = null
    task.statusId = 'TASK_CREATED'
    syncWorkEffortAssignment(delegator, task, null, now)
    CaseRuntimeHelper.createCaseEvent(delegator, task.caseId, 'CASE_TASK_ASSIGNED', loginId, now, [
            fromStageSeq : task.stageSeq,
            stageVisitSeq: task.stageVisitSeq,
            fromStepSeq  : task.stepSeq,
            detail       : "Task released to queue ${task.assignedQueueId ?: ''}"
    ])
    return success([caseTaskId: task.caseTaskId, assignedQueueId: task.assignedQueueId, statusId: task.statusId])
}

Map getQueueWorkload() {
    Map workload = CaseAssignmentHelper.getQueueWorkload(delegator, parameters.queueId, UtilDateTime.nowTimestamp())
    return success(workload)
}

Map getAssignableParties() {
    Map workload = CaseAssignmentHelper.getQueueWorkload(delegator, parameters.queueId, UtilDateTime.nowTimestamp())
    return success(workload)
}

private void syncWorkEffortAssignment(org.apache.ofbiz.entity.Delegator delegator, GenericValue task,
                                      String partyId, java.sql.Timestamp now) {
    if (!task.workEffortId) {
        return
    }
    List<GenericValue> existing = EntityQuery.use(delegator)
            .from('WorkEffortPartyAssignment')
            .where(EntityCondition.makeCondition([
                    workEffortId: task.workEffortId,
                    roleTypeId  : 'CAL_OWNER'
            ]))
            .queryList()
            .findAll { GenericValue assignment -> assignment.thruDate == null }
    existing.each { GenericValue assignment ->
        assignment.thruDate = now
        assignment.store()
    }
    if (partyId) {
        delegator.create('WorkEffortPartyAssignment', [
                workEffortId   : task.workEffortId,
                partyId        : partyId,
                roleTypeId     : 'CAL_OWNER',
                fromDate       : now,
                statusId       : 'PRTYASGN_ASSIGNED',
                statusDateTime : now
        ])
    }
}
