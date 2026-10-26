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
package org.apache.ofbiz.casemgmt.cases

import java.sql.Timestamp
import java.util.Calendar

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.casemgmt.access.CaseAccessHelper
import org.apache.ofbiz.casemgmt.flow.CaseFlowEngine
import org.apache.ofbiz.casemgmt.sla.CaseSlaHelper
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.entity.transaction.TransactionUtil
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

/**
 * Case lifecycle services for the Phase 1 kernel.
 */
Map createCase() {
    String caseTypeId = parameters.caseTypeId
    GenericValue caseType = from('CaseType').where(caseTypeId: caseTypeId).queryOne()
    if (caseType == null) {
        return error("Case type not found: $caseTypeId")
    }
    if (caseType.statusId != 'CASE_TYPE_ACTIVE') {
        return error("Case type is not active: $caseTypeId")
    }

    Timestamp now = UtilDateTime.nowTimestamp()
    String caseId = delegator.getNextSeqId('WorkEffort')
    String caseNumber = 'CASE-' + caseId
    String loginId = userLogin?.userLoginId ?: 'system'
    Double priority = parameters.priority as Double
    long definitionVersion = (caseType.activeDefinitionVersion ?: caseType.definitionVersion ?: 1) as long

    CaseRuntimeHelper.createCaseWorkEffort(delegator, caseId, caseNumber,
            parameters.description, priority, loginId, now)
    delegator.create('Case', [
            caseId                : caseId,
            caseTypeId            : caseTypeId,
            caseNumber            : caseNumber,
            currentStageSeq       : 1,
            currentStageVisitSeq  : 1,
            currentStepSeq        : 1,
            ownerPartyId          : parameters.ownerPartyId,
            customerPartyId       : parameters.customerPartyId,
            priority              : priority ?: 3,
            channelEnumId         : parameters.channelEnumId,
            openedDate            : now,
            accessPolicyId        : caseType.accessPolicyId,
            definitionVersion     : definitionVersion,
            version               : 1,
            description           : parameters.description
    ])
    Map slaResult = CaseSlaHelper.createSlaInstances(delegator, dispatcher, userLogin, caseId, now)
    if (ServiceUtil.isError(slaResult)) {
        TransactionUtil.setRollbackOnly(ServiceUtil.getErrorMessage(slaResult), null)
        return slaResult
    }
    CaseRuntimeHelper.createCaseEvent(delegator, caseId, 'CASE_EVENT_CREATED', loginId, now, [
            toStageSeq   : 1,
            stageVisitSeq: 1,
            toStepSeq    : 1,
            detail       : 'Case created'
    ])
    Map stageResult = CaseFlowEngine.startStage(delegator, dispatcher, userLogin, caseId, definitionVersion,
            1, 1, parameters.ownerPartyId, now)
    if (ServiceUtil.isError(stageResult)) {
        TransactionUtil.setRollbackOnly(ServiceUtil.getErrorMessage(stageResult), null)
        return stageResult
    }
    Map autoResult = CaseFlowEngine.runAutoAdvance(delegator, dispatcher, userLogin, caseId, now)
    if (ServiceUtil.isError(autoResult)) {
        TransactionUtil.setRollbackOnly(ServiceUtil.getErrorMessage(autoResult), null)
        return autoResult
    }
    return success([caseId: caseId, caseNumber: caseNumber])
}

Map listCases() {
    List<GenericValue> caseValues = from('Case').orderBy('-openedDate').queryList()
    caseValues = CaseAccessHelper.filterVisibleCases(delegator, dispatcher, userLogin, caseValues)

    Set<String> caseIds = caseValues.collect { GenericValue caseValue -> caseValue.caseId as String } as Set<String>
    Map<String, GenericValue> workEffortsById = [:]
    List<GenericValue> allTasks = []
    List<GenericValue> allSlaInstances = []
    if (!caseIds.isEmpty()) {
        List<String> idList = new ArrayList<>(caseIds)
        workEffortsById = EntityQuery.use(delegator)
                .from('WorkEffort')
                .where(EntityCondition.makeCondition('workEffortId', EntityOperator.IN, idList))
                .queryList()
                .collectEntries { GenericValue value -> [(value.workEffortId as String): value] }
        allTasks = EntityQuery.use(delegator)
                .from('CaseTask')
                .where(EntityCondition.makeCondition('caseId', EntityOperator.IN, idList))
                .queryList()
        allSlaInstances = EntityQuery.use(delegator)
                .from('CaseSlaInstance')
                .where(EntityCondition.makeCondition('caseId', EntityOperator.IN, idList))
                .queryList()
    }
    Map<String, List<GenericValue>> openTasksByCase = [:]
    allTasks.findAll { GenericValue task ->
        ['TASK_CREATED', 'TASK_ASSIGNED', 'TASK_IN_PROGRESS', 'TASK_WAITING'].contains(task.statusId)
    }.each { GenericValue task ->
        List<GenericValue> tasksForCase = openTasksByCase[task.caseId as String]
        if (tasksForCase == null) {
            tasksForCase = []
            openTasksByCase[task.caseId as String] = tasksForCase
        }
        tasksForCase << task
    }
    Map<String, List<GenericValue>> slaByCase = [:]
    allSlaInstances.each { GenericValue instance ->
        List<GenericValue> instancesForCase = slaByCase[instance.caseId as String]
        if (instancesForCase == null) {
            instancesForCase = []
            slaByCase[instance.caseId as String] = instancesForCase
        }
        instancesForCase << instance
    }

    String assignedToMe = parameters.assignedToMe?.toString()
    String partyId = userLogin?.partyId
    String caseTypeId = parameters.caseTypeId?.toString()?.trim()
    String statusId = parameters.statusId?.toString()?.trim()
    String stageFilter = parameters.stageSeq?.toString()?.trim()
    String queueId = parameters.queueId?.toString()?.trim()
    String assignedPartyId = parameters.assignedPartyId?.toString()?.trim()
    String slaStateFilter = parameters.slaStateEnumId?.toString()?.trim()
    String slaMilestoneFilter = parameters.slaMilestoneName?.toString()?.trim()
    String ageBucketFilter = parameters.ageBucket?.toString()?.trim()
    String openOnly = parameters.openOnly?.toString()
    Timestamp openedFrom = parseDate(parameters.fromDate, false)
    Timestamp openedTo = parseDate(parameters.toDate, true)
    Timestamp closedFrom = parseDate(parameters.closedFromDate, false)
    Timestamp closedTo = parseDate(parameters.closedToDate, true)
    Map closedPeriod = parseClosedPeriod(parameters.closedPeriod)
    if (closedFrom == null) {
        closedFrom = closedPeriod.from as Timestamp
    }
    if (closedTo == null) {
        closedTo = closedPeriod.to as Timestamp
    }
    boolean explicitClosedRange = closedFrom != null || closedTo != null
    String closedWithin = parameters.closedWithin?.toString()?.toUpperCase() ?: 'NONE'
    Timestamp closedCutoff = closedCutoff(closedWithin)
    List<String> closedStatuses = ['CASE_CLOSED', 'CASE_CANCELLED']
    Timestamp now = UtilDateTime.nowTimestamp()

    List<GenericValue> filtered = caseValues.findAll { GenericValue caseValue ->
        GenericValue workEffort = workEffortsById[caseValue.caseId]
        String status = workEffort?.currentStatusId
        List<GenericValue> openTasks = openTasksByCase[caseValue.caseId] ?: []
        List<GenericValue> slaInstances = slaByCase[caseValue.caseId] ?: []

        if (caseTypeId && caseTypeId != caseValue.caseTypeId) {
            return false
        }
        if (statusId && statusId != status) {
            return false
        }
        if (stageFilter) {
            if (stageFilter == 'NONE') {
                if (caseValue.currentStageSeq != null) {
                    return false
                }
            } else if ((caseValue.currentStageSeq ?: -1L) as long != (stageFilter as long)) {
                return false
            }
        }
        if (openedFrom && caseValue.openedDate && caseValue.openedDate.before(openedFrom)) {
            return false
        }
        if (openedTo && caseValue.openedDate && caseValue.openedDate.after(openedTo)) {
            return false
        }
        if (queueId) {
            boolean matchesQueue = queueId == 'UNASSIGNED' ?
                    openTasks.any { GenericValue task -> !task.assignedQueueId } :
                    openTasks.any { GenericValue task -> task.assignedQueueId == queueId }
            if (!matchesQueue) {
                return false
            }
        }
        if (assignedPartyId) {
            boolean matchesAssignee = assignedPartyId == 'UNASSIGNED' ?
                    openTasks.any { GenericValue task -> !task.assignedPartyId } :
                    openTasks.any { GenericValue task -> task.assignedPartyId == assignedPartyId }
            if (!matchesAssignee) {
                return false
            }
        }
        if (['Y', 'y', 'true', '1'].contains(assignedToMe)) {
            if (!partyId || !openTasks.any { GenericValue task -> task.assignedPartyId == partyId }) {
                return false
            }
        }
        if (slaStateFilter) {
            if (slaStateFilter == 'NO_SLA') {
                if (caseValue.slaStateEnumId != null) {
                    return false
                }
            } else if (caseValue.slaStateEnumId != slaStateFilter) {
                return false
            }
        }
        if (slaMilestoneFilter && !slaInstances.any { GenericValue instance ->
            instance.milestoneName == slaMilestoneFilter
        }) {
            return false
        }
        if (['Y', 'y', 'true', '1'].contains(openOnly) && closedStatuses.contains(status)) {
            return false
        }
        if (explicitClosedRange) {
            if (!caseValue.closedDate) {
                return false
            }
            if (closedFrom && caseValue.closedDate.before(closedFrom)) {
                return false
            }
            if (closedTo && caseValue.closedDate.after(closedTo)) {
                return false
            }
        } else if (closedStatuses.contains(status)) {
            if (closedCutoff == null || !caseValue.closedDate || caseValue.closedDate.before(closedCutoff)) {
                return false
            }
        }
        if (ageBucketFilter) {
            if (closedStatuses.contains(status) || caseValue.closedDate || !caseValue.openedDate) {
                return false
            }
            long minutes = (now.time - caseValue.openedDate.time) / 60000L
            if (agingBucket(minutes) != ageBucketFilter) {
                return false
            }
        }
        return true
    }

    List<Map<String, Object>> cases = filtered.collect { GenericValue caseValue ->
        GenericValue workEffort = workEffortsById[caseValue.caseId]
        Map<String, Object> caseMap = new HashMap<>(caseValue.getAllFields())
        caseMap.put('statusId', workEffort?.currentStatusId)
        caseMap.put('workEffortName', workEffort?.workEffortName)
        return caseMap
    }
    return success([cases: cases])
}

Map getCase() {
    String caseId = parameters.caseId
    GenericValue caseValue = from('Case').where(caseId: caseId).queryOne()
    if (caseValue == null) {
        return error("Case not found: $caseId")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_VIEW')) {
        return error(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_VIEW'))
    }
    GenericValue workEffort = from('WorkEffort').where(workEffortId: caseId).queryOne()
    List<GenericValue> tasks = from('CaseTask').where(caseId: caseId).orderBy('caseTaskId').queryList()
    List<GenericValue> history = from('CaseEvent').where(caseId: caseId).orderBy('eventDate').queryList()

    Map<String, Object> caseMap = new HashMap<>(caseValue.getAllFields())
    caseMap.put('statusId', workEffort?.currentStatusId)
    caseMap.put('workEffortName', workEffort?.workEffortName)

    List<Map<String, Object>> taskMaps = tasks.collect { GenericValue task ->
        Map<String, Object> taskMap = new HashMap<>(task.getAllFields())
        if (task.workEffortId) {
            GenericValue taskWorkEffort = from('WorkEffort')
                    .where(workEffortId: task.workEffortId)
                    .queryOne()
            taskMap.put('workEffortStatusId', taskWorkEffort?.currentStatusId)
        }
        return taskMap
    }
    List<Map<String, Object>> historyMaps = history.collect { GenericValue event ->
        return new HashMap<>(event.getAllFields())
    }
    return success([
            case       : caseMap,
            caseHeader : caseMap,
            tasks      : taskMaps,
            history    : historyMaps,
            permissions: CaseAccessHelper.getCasePermissions(delegator, dispatcher, userLogin, caseValue)
    ])
}

Map completeCaseTask() {
    String caseTaskId = parameters.caseTaskId
    GenericValue task = from('CaseTask').where(caseTaskId: caseTaskId).queryOne()
    if (task == null) {
        return error("Case task not found: $caseTaskId")
    }
    if (['TASK_COMPLETED', 'TASK_CANCELLED', 'TASK_SKIPPED'].contains(task.statusId)) {
        return error("Case task is already closed: $caseTaskId")
    }
    GenericValue accessCase = from('Case').where(caseId: task.caseId).queryOne()
    if (accessCase == null) {
        return error("Case not found: ${task.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, accessCase, 'CASE_UPDATE')) {
        return error(CaseAccessHelper.permissionError(userLogin, accessCase, 'CASE_UPDATE'))
    }
    GenericValue validChange = from('StatusValidChange')
            .where('statusId', task.statusId, 'statusIdTo', 'TASK_COMPLETED')
            .queryOne()
    if (validChange == null) {
        return error("Invalid task status transition: ${task.statusId} -> TASK_COMPLETED")
    }
    if (!CaseConcurrencyHelper.lockCase(delegator, task.caseId)) {
        return error("Case was updated by another operation; reload and retry")
    }
    GenericValue currentCase = from('Case').where(caseId: task.caseId).queryOne()
    if (currentCase == null ||
            (task.stageSeq as long) != (currentCase.currentStageSeq as long) ||
            (task.stageVisitSeq as long) != (currentCase.currentStageVisitSeq as long)) {
        return error("Case task is no longer in the current stage: $caseTaskId")
    }

    Timestamp now = UtilDateTime.nowTimestamp()
    String loginId = userLogin?.userLoginId ?: 'system'
    String outcomeEnumId = parameters.outcomeEnumId ?: 'CASE_OUT_COMPLETE'
    if (!CaseConcurrencyHelper.updateTask(delegator, task.caseTaskId, task.statusId, [
            statusId           : 'TASK_COMPLETED',
            completedDate      : now,
            completedByUserLogin: loginId,
            outcomeEnumId      : outcomeEnumId,
            notes              : parameters.notes
    ])) {
        return error("Case task was already processed: $caseTaskId")
    }
    task.statusId = 'TASK_COMPLETED'
    task.completedDate = now
    task.completedByUserLogin = loginId
    task.outcomeEnumId = outcomeEnumId
    task.notes = parameters.notes

    if (task.workEffortId) {
        CaseRuntimeHelper.updateAssignmentWorkEffort(delegator, task.workEffortId,
                'TASK_COMPLETED', loginId, now)
    }
    CaseRuntimeHelper.createCaseEvent(delegator, task.caseId, 'CASE_TASK_COMPLETED', loginId, now, [
            fromStageSeq   : task.stageSeq,
            stageVisitSeq  : task.stageVisitSeq,
            fromStepSeq    : task.stepSeq,
            detail         : "Task completed: $caseTaskId"
    ])

    if ((task.stageSeq as long) == 1L && (task.stepSeq as long) == 1L) {
        Map firstResponseResult = CaseSlaHelper.completeMilestoneType(delegator, userLogin,
                task.caseId, 'SLA_FIRST_RESPONSE', now)
        if (ServiceUtil.isError(firstResponseResult)) {
            TransactionUtil.setRollbackOnly(ServiceUtil.getErrorMessage(firstResponseResult), null)
            return firstResponseResult
        }
    }

    Map engineResult = CaseFlowEngine.onCaseTaskCompleted(delegator, dispatcher, userLogin, task.caseId,
            task.definitionVersion as long, task.stageSeq as long, task.stageVisitSeq as long,
            task.stepSeq as long, task.outcomeEnumId, now)
    if (ServiceUtil.isError(engineResult)) {
        TransactionUtil.setRollbackOnly(ServiceUtil.getErrorMessage(engineResult), null)
        return engineResult
    }
    Map autoResult = CaseFlowEngine.runAutoAdvance(delegator, dispatcher, userLogin, task.caseId, now)
    if (ServiceUtil.isError(autoResult)) {
        TransactionUtil.setRollbackOnly(ServiceUtil.getErrorMessage(autoResult), null)
        return autoResult
    }

    GenericValue caseValue = from('Case').where(caseId: task.caseId).queryOne()
    GenericValue workEffort = from('WorkEffort').where(workEffortId: task.caseId).queryOne()
    return success([
            caseId              : task.caseId,
            statusId            : workEffort?.currentStatusId,
            currentStageSeq     : caseValue?.currentStageSeq,
            currentStageVisitSeq: caseValue?.currentStageVisitSeq,
            currentStepSeq      : caseValue?.currentStepSeq,
            advanced            : engineResult.advanced,
            exitCondition       : engineResult.exitCondition,
            resolved            : engineResult.resolved || autoResult.resolved
    ])
}

Map signalCaseTask() {
    String caseTaskId = parameters.caseTaskId
    String idempotencyKey = parameters.idempotencyKey?.trim()
    if (!idempotencyKey) {
        return error('idempotencyKey is required')
    }

    GenericValue existingEvent = findSignalEvent(idempotencyKey)
    if (existingEvent != null) {
        return idempotentSignalResult(existingEvent, caseTaskId)
    }

    GenericValue task = from('CaseTask').where(caseTaskId: caseTaskId).queryOne()
    if (task == null) {
        return error("Case task not found: $caseTaskId")
    }
    if (task.statusId != 'TASK_WAITING') {
        return error("Case task is not waiting: $caseTaskId")
    }
    GenericValue accessCase = from('Case').where(caseId: task.caseId).queryOne()
    if (accessCase == null) {
        return error("Case not found: ${task.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, accessCase, 'CASE_UPDATE')) {
        return error(CaseAccessHelper.permissionError(userLogin, accessCase, 'CASE_UPDATE'))
    }
    GenericValue waitConfig = from('CaseStepWait').where(EntityCondition.makeCondition([
            caseTypeId       : accessCase.caseTypeId,
            definitionVersion: task.definitionVersion,
            stageSeq         : task.stageSeq,
            stepSeq          : task.stepSeq
    ])).queryOne()
    String signalName = parameters.signalName?.trim()
    if (waitConfig?.signalName && waitConfig.signalName != signalName) {
        return error("Signal name mismatch for ${caseTaskId}: expected ${waitConfig.signalName}")
    }

    if (!CaseConcurrencyHelper.lockCase(delegator, task.caseId)) {
        existingEvent = findSignalEvent(idempotencyKey)
        if (existingEvent != null) {
            return idempotentSignalResult(existingEvent, caseTaskId)
        }
        return error("Case was updated by another operation; reload and retry")
    }
    existingEvent = findSignalEvent(idempotencyKey)
    if (existingEvent != null) {
        return idempotentSignalResult(existingEvent, caseTaskId)
    }
    task = from('CaseTask').where(caseTaskId: caseTaskId).queryOne()
    if (task == null || task.statusId != 'TASK_WAITING') {
        return error("Case task was already signaled or processed: $caseTaskId")
    }
    GenericValue currentCase = from('Case').where(caseId: task.caseId).queryOne()
    if (currentCase == null ||
            (task.stageSeq as long) != (currentCase.currentStageSeq as long) ||
            (task.stageVisitSeq as long) != (currentCase.currentStageVisitSeq as long)) {
        return error("Case task is no longer in the current stage: $caseTaskId")
    }

    Timestamp now = UtilDateTime.nowTimestamp()
    String loginId = userLogin?.userLoginId ?: 'system'
    String outcomeEnumId = parameters.outcomeEnumId ?: 'CASE_OUT_COMPLETE'
    String notes = parameters.notes ?: parameters.payload
    if (!CaseConcurrencyHelper.updateTask(delegator, task.caseTaskId, 'TASK_WAITING', [
            statusId           : 'TASK_COMPLETED',
            completedDate      : now,
            completedByUserLogin: loginId,
            outcomeEnumId      : outcomeEnumId,
            notes              : notes
    ])) {
        return error("Case task was already signaled or processed: $caseTaskId")
    }
    task.statusId = 'TASK_COMPLETED'
    task.completedDate = now
    task.completedByUserLogin = loginId
    task.outcomeEnumId = outcomeEnumId
    task.notes = notes

    CaseRuntimeHelper.createCaseEvent(delegator, task.caseId, 'CASE_TASK_SIGNALED', loginId, now, [
            fromStageSeq : task.stageSeq,
            stageVisitSeq: task.stageVisitSeq,
            fromStepSeq  : task.stepSeq,
            refEntityName: 'CaseTask',
            refPkValue   : task.caseTaskId,
            idempotencyKey: idempotencyKey,
            detail       : "Task signaled: $caseTaskId / ${task.outcomeEnumId}"
    ])
    Map engineResult = CaseFlowEngine.onCaseTaskCompleted(delegator, dispatcher, userLogin, task.caseId,
            task.definitionVersion as long, task.stageSeq as long, task.stageVisitSeq as long,
            task.stepSeq as long, task.outcomeEnumId, now)
    if (ServiceUtil.isError(engineResult)) {
        TransactionUtil.setRollbackOnly(ServiceUtil.getErrorMessage(engineResult), null)
        return engineResult
    }
    Map autoResult = CaseFlowEngine.runAutoAdvance(delegator, dispatcher, userLogin, task.caseId, now)
    if (ServiceUtil.isError(autoResult)) {
        TransactionUtil.setRollbackOnly(ServiceUtil.getErrorMessage(autoResult), null)
        return autoResult
    }

    GenericValue caseValue = from('Case').where(caseId: task.caseId).queryOne()
    GenericValue workEffort = from('WorkEffort').where(workEffortId: task.caseId).queryOne()
    return success([
            caseId              : task.caseId,
            statusId            : workEffort?.currentStatusId,
            currentStageSeq     : caseValue?.currentStageSeq,
            currentStageVisitSeq: caseValue?.currentStageVisitSeq,
            currentStepSeq      : caseValue?.currentStepSeq,
            advanced            : engineResult.advanced,
            resolved            : engineResult.resolved || autoResult.resolved,
            idempotent          : false
    ])
}

Map closeCase() {
    String caseId = parameters.caseId
    GenericValue caseValue = from('Case').where(caseId: caseId).queryOne()
    if (caseValue == null) {
        return error("Case not found: $caseId")
    }
    GenericValue workEffort = from('WorkEffort').where(workEffortId: caseId).queryOne()
    if (workEffort == null) {
        return error("Case WorkEffort not found: $caseId")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_CLOSE')) {
        return error(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_CLOSE'))
    }
    if (workEffort.currentStatusId == 'CASE_CLOSED') {
        return success([caseId: caseId, statusId: 'CASE_CLOSED'])
    }
    if (!CaseConcurrencyHelper.lockCase(delegator, caseId)) {
        return error("Case was updated by another operation; reload and retry")
    }
    caseValue = from('Case').where(caseId: caseId).queryOne()

    Timestamp now = UtilDateTime.nowTimestamp()
    String loginId = userLogin?.userLoginId ?: 'system'
    try {
        CaseRuntimeHelper.changeCaseStatus(delegator, caseId, 'CASE_CLOSED', loginId, now, 'Case closed')
    } catch (IllegalStateException e) {
        return error(e.message)
    }
    caseValue.closedDate = now
    caseValue.resolutionId = parameters.resolutionId
    caseValue.version = (caseValue.version ?: 0) + 1
    caseValue.store()

    List<GenericValue> openTasks = CaseRuntimeHelper.getOpenTasks(delegator, caseId)
    openTasks.each { GenericValue task ->
        task.statusId = 'TASK_CANCELLED'
        task.completedDate = now
        task.completedByUserLogin = loginId
        task.store()
        if (task.workEffortId) {
            CaseRuntimeHelper.updateAssignmentWorkEffort(delegator, task.workEffortId,
                    'TASK_CANCELLED', loginId, now)
        }
    }
    CaseRuntimeHelper.createCaseEvent(delegator, caseId, 'CASE_CLOSED', loginId, now, [
            detail: "Case closed; ${openTasks.size()} open task(s) cancelled"
    ])
    Map slaResult = CaseSlaHelper.completeSlaInstances(delegator, userLogin, caseId, now)
    if (ServiceUtil.isError(slaResult)) {
        TransactionUtil.setRollbackOnly(ServiceUtil.getErrorMessage(slaResult), null)
        return slaResult
    }
    Map subCaseResult = CaseFlowEngine.onSubCaseClosed(delegator, dispatcher, userLogin, caseId, now)
    if (ServiceUtil.isError(subCaseResult)) {
        TransactionUtil.setRollbackOnly(ServiceUtil.getErrorMessage(subCaseResult), null)
        return subCaseResult
    }
    CaseServiceMappingHelper.deleteCaseVariables(delegator, caseId)
    return success([caseId: caseId, statusId: 'CASE_CLOSED'])
}

Map explainCaseState() {
    String caseId = parameters.caseId
    GenericValue caseValue = from('Case').where(caseId: caseId).queryOne()
    if (caseValue == null) {
        return error("Case not found: $caseId")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_VIEW')) {
        return error(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_VIEW'))
    }
    GenericValue workEffort = from('WorkEffort').where(workEffortId: caseId).queryOne()
    if (workEffort == null) {
        return error("Case WorkEffort not found: $caseId")
    }

    GenericValue currentStageDef = from('CaseStageDef')
            .where(caseTypeId: caseValue.caseTypeId,
                    definitionVersion: caseValue.definitionVersion,
                    stageSeq: caseValue.currentStageSeq)
            .queryOne()
    GenericValue currentStepDef = from('CaseStepDef')
            .where(caseTypeId: caseValue.caseTypeId,
                    definitionVersion: caseValue.definitionVersion,
                    stageSeq: caseValue.currentStageSeq,
                    stepSeq: caseValue.currentStepSeq)
            .queryOne()
    List<GenericValue> openTasks = CaseRuntimeHelper.getOpenTasks(delegator, caseId)
    List<String> blockedReasons = []
    List<String> nextSteps = []
    openTasks.each { GenericValue task ->
        GenericValue step = from('CaseStepDef')
                .where(caseTypeId: caseValue.caseTypeId,
                        definitionVersion: caseValue.definitionVersion,
                        stageSeq: task.stageSeq,
                        stepSeq: task.stepSeq)
                .queryOne()
        String stepName = step?.stepName ?: "Step ${task.stepSeq}"
        blockedReasons << "Task ${stepName} is ${task.statusId}"
        nextSteps << stepName
    }
    List<GenericValue> conditionEvents = from('CaseEvent')
            .where(caseId: caseId, eventTypeEnumId: 'CASE_FLOW_CONDITION')
            .orderBy('-eventDate')
            .queryList()
    if (openTasks.isEmpty() && !conditionEvents.isEmpty() && conditionEvents[0].conditionResult == 'false') {
        blockedReasons << 'Stage exit condition is not satisfied'
    }
    Map permissions = CaseAccessHelper.getCasePermissions(delegator, dispatcher, userLogin, caseValue)
    List<String> nextActions = []
    if (permissions.update && !openTasks.isEmpty()) {
        nextActions << 'Complete the current task'
    }
    if (permissions.assign && !openTasks.isEmpty()) {
        nextActions << 'Assign or reassign a task'
    }
    if (permissions.close) {
        nextActions << 'Close the case'
    }
    List<GenericValue> slaInstances = from('CaseSlaInstance').where(caseId: caseId).queryList()
    List<Map<String, Object>> slaRows = slaInstances.collect { GenericValue instance ->
        [
                milestoneSeq  : instance.milestoneSeq,
                milestoneName : instance.milestoneName,
                slaStateEnumId: instance.slaStateEnumId,
                goalDate      : instance.goalDate,
                deadlineDate  : instance.deadlineDate
        ]
    }
    return success([
            statusId            : workEffort.currentStatusId,
            stageName           : currentStageDef?.stageName,
            stepName            : currentStepDef?.stepName,
            currentStageSeq     : caseValue.currentStageSeq,
            currentStageVisitSeq: caseValue.currentStageVisitSeq,
            currentStepSeq      : caseValue.currentStepSeq,
            openTaskCount       : openTasks.size(),
            blockedReasons      : blockedReasons,
            nextSteps           : nextSteps,
            nextActions         : nextActions,
            slaState            : caseValue.slaStateEnumId,
            sla                 : slaRows,
            permissions         : permissions
    ])
}

private GenericValue findSignalEvent(String idempotencyKey) {
    return from('CaseEvent').where('idempotencyKey', idempotencyKey).queryOne()
}

private Map idempotentSignalResult(GenericValue event, String caseTaskId) {
    if (event.eventTypeEnumId != 'CASE_TASK_SIGNALED' || event.refPkValue != caseTaskId) {
        return ServiceUtil.returnError("Idempotency key already used: ${event.idempotencyKey}")
    }
    GenericValue caseValue = from('Case').where('caseId', event.caseId).queryOne()
    GenericValue workEffort = from('WorkEffort').where('workEffortId', event.caseId).queryOne()
    Map result = ServiceUtil.returnSuccess()
    result.putAll([
            caseId              : event.caseId,
            statusId            : workEffort?.currentStatusId,
            currentStageSeq     : caseValue?.currentStageSeq,
            currentStageVisitSeq: caseValue?.currentStageVisitSeq,
            currentStepSeq      : caseValue?.currentStepSeq,
            advanced            : false,
            resolved            : false,
            idempotent          : true
    ])
    return result
}

private Timestamp parseDate(Object value, boolean endOfDay) {
    if (value == null) {
        return null
    }
    String text = value.toString().trim()
    if (!text) {
        return null
    }
    try {
        if (text ==~ /\d{4}-\d{2}-\d{2}/) {
            return Timestamp.valueOf(text + (endOfDay ? ' 23:59:59' : ' 00:00:00'))
        }
        return Timestamp.valueOf(text)
    } catch (Exception ignored) {
        return null
    }
}

private Map parseClosedPeriod(Object value) {
    if (value == null) {
        return [:]
    }
    String period = value.toString().trim()
    if (!period) {
        return [:]
    }
    try {
        if (period ==~ /\d{4}-\d{2}-\d{2}/) {
            return [from: parseDate(period, false), to: parseDate(period, true)]
        }
        if (period ==~ /\d{4}-\d{2}/) {
            int year = period.substring(0, 4) as int
            int month = period.substring(5, 7) as int
            Calendar start = Calendar.getInstance()
            start.clear()
            start.set(year, month - 1, 1)
            Calendar end = Calendar.getInstance()
            end.clear()
            end.set(year, month - 1, start.getActualMaximum(Calendar.DAY_OF_MONTH))
            return [from: startOfDay(start.time), to: endOfDay(end.time)]
        }
        if (period ==~ /\d{4}-W\d{2}/) {
            int year = period.substring(0, 4) as int
            int week = period.substring(6, 8) as int
            Calendar start = Calendar.getInstance()
            start.clear()
            start.set(Calendar.YEAR, year)
            start.set(Calendar.WEEK_OF_YEAR, week)
            start.set(Calendar.DAY_OF_WEEK, start.getFirstDayOfWeek())
            Calendar end = Calendar.getInstance()
            end.time = start.time
            end.add(Calendar.DAY_OF_MONTH, 6)
            return [from: startOfDay(start.time), to: endOfDay(end.time)]
        }
    } catch (Exception ignored) {
        return [:]
    }
    return [:]
}

private Timestamp closedCutoff(String closedWithin) {
    long days
    switch (closedWithin) {
        case 'WEEK':
            days = 7L
            break
        case 'MONTH':
            days = 30L
            break
        case 'YEAR':
            days = 365L
            break
        case 'ALL':
            return new Timestamp(0L)
        default:
            return null
    }
    return new Timestamp(UtilDateTime.nowTimestamp().time - (days * 24L * 60L * 60L * 1000L))
}

private Timestamp startOfDay(Date date) {
    Calendar calendar = Calendar.getInstance()
    calendar.time = date
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return new Timestamp(calendar.timeInMillis)
}

private Timestamp endOfDay(Date date) {
    Calendar calendar = Calendar.getInstance()
    calendar.time = date
    calendar.set(Calendar.HOUR_OF_DAY, 23)
    calendar.set(Calendar.MINUTE, 59)
    calendar.set(Calendar.SECOND, 59)
    calendar.set(Calendar.MILLISECOND, 999)
    return new Timestamp(calendar.timeInMillis)
}

private String agingBucket(long minutes) {
    long days = minutes / (60L * 24L)
    if (days < 1) {
        return '0-1d'
    }
    if (days < 3) {
        return '1-3d'
    }
    if (days < 7) {
        return '3-7d'
    }
    if (days < 14) {
        return '7-14d'
    }
    return '14d+'
}
