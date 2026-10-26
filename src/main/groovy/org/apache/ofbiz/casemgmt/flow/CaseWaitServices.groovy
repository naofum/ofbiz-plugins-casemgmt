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
package org.apache.ofbiz.casemgmt.flow

import java.sql.Timestamp

import org.apache.ofbiz.base.util.Debug
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

/**
 * Wait step configuration and timeout processing.
 */

Map getCaseStepWait() {
    GenericValue caseType = EntityQuery.use(delegator).from('CaseType')
            .where('caseTypeId', parameters.caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    long version = (parameters.definitionVersion ?: caseType.definitionVersion ?: 1) as long
    GenericValue wait = EntityQuery.use(delegator).from('CaseStepWait')
            .where(EntityCondition.makeCondition([
                    caseTypeId       : caseType.caseTypeId,
                    definitionVersion: version,
                    stageSeq         : (parameters.stageSeq ?: 1) as long,
                    stepSeq          : (parameters.stepSeq ?: 1) as long
            ]))
            .queryOne()
    return successMap([
            caseTypeId       : caseType.caseTypeId,
            definitionVersion: version,
            stageSeq         : (parameters.stageSeq ?: 1) as long,
            stepSeq          : (parameters.stepSeq ?: 1) as long,
            stepWait         : wait == null ? [:] : new LinkedHashMap<>(wait.getAllFields())
    ])
}

Map saveCaseStepWait() {
    Map check = checkDraft(parameters.caseTypeId, parameters.definitionVersion)
    if (check.error) {
        return errorMap(check.error)
    }
    long stageSeq = (parameters.stageSeq ?: 1) as long
    long stepSeq = (parameters.stepSeq ?: 1) as long
    GenericValue step = EntityQuery.use(delegator).from('CaseStepDef')
            .where(EntityCondition.makeCondition([
                    caseTypeId       : check.caseTypeId,
                    definitionVersion: check.definitionVersion,
                    stageSeq         : stageSeq,
                    stepSeq          : stepSeq
            ])).queryOne()
    if (step == null) {
        return errorMap("Case step not found: ${check.caseTypeId}/${stageSeq}/${stepSeq}")
    }
    if (step.stepTypeEnumId != 'CASE_STEP_WAIT') {
        return errorMap("Wait settings can only be configured for CASE_STEP_WAIT: $stepSeq")
    }
    String signalName = parameters.signalName?.trim() ?: null
    String timeoutOutcomeEnumId = parameters.timeoutOutcomeEnumId?.trim() ?: null
    if (timeoutOutcomeEnumId && !enumExists('CASE_OUTCOME', timeoutOutcomeEnumId)) {
        return errorMap("Invalid timeout outcome: $timeoutOutcomeEnumId")
    }
    long timeoutDuration = (parameters.timeoutDuration ?: 0) as long
    if (timeoutDuration < 0) {
        return errorMap('timeoutDuration must be zero or greater')
    }
    if (timeoutDuration > 0 && !timeoutOutcomeEnumId) {
        return errorMap('timeoutOutcomeEnumId is required when timeoutDuration is greater than zero')
    }

    Map<String, Object> fields = [
            signalName           : signalName,
            timeoutDuration      : timeoutDuration > 0 ? timeoutDuration : null,
            timeoutOutcomeEnumId : timeoutOutcomeEnumId,
            description          : parameters.description
    ]
    GenericValue existing = EntityQuery.use(delegator).from('CaseStepWait')
            .where(EntityCondition.makeCondition([
                    caseTypeId       : check.caseTypeId,
                    definitionVersion: check.definitionVersion,
                    stageSeq         : stageSeq,
                    stepSeq          : stepSeq
            ])).queryOne()
    if (existing == null) {
        fields.caseTypeId = check.caseTypeId
        fields.definitionVersion = check.definitionVersion
        fields.stageSeq = stageSeq
        fields.stepSeq = stepSeq
        delegator.create('CaseStepWait', fields)
    } else {
        existing.setFields(fields)
        existing.store()
    }
    return successMap([
            caseTypeId       : check.caseTypeId,
            definitionVersion: check.definitionVersion,
            stageSeq         : stageSeq,
            stepSeq          : stepSeq
    ])
}

Map deleteCaseStepWait() {
    Map check = checkDraft(parameters.caseTypeId, parameters.definitionVersion)
    if (check.error) {
        return errorMap(check.error)
    }
    long stageSeq = (parameters.stageSeq ?: 1) as long
    long stepSeq = (parameters.stepSeq ?: 1) as long
    GenericValue wait = EntityQuery.use(delegator).from('CaseStepWait')
            .where(EntityCondition.makeCondition([
                    caseTypeId       : check.caseTypeId,
                    definitionVersion: check.definitionVersion,
                    stageSeq         : stageSeq,
                    stepSeq          : stepSeq
            ])).queryOne()
    if (wait != null) {
        delegator.removeValue(wait)
    }
    return successMap([
            caseTypeId       : check.caseTypeId,
            definitionVersion: check.definitionVersion,
            stageSeq         : stageSeq,
            stepSeq          : stepSeq
    ])
}

Map processCaseWaitTimeouts() {
    Timestamp now = UtilDateTime.nowTimestamp()
    GenericValue serviceUser = userLogin ?: EntityQuery.use(delegator).from('UserLogin')
            .where('userLoginId', 'system').queryOne()
    List<GenericValue> waitTasks = EntityQuery.use(delegator)
            .from('CaseTask')
            .where(EntityCondition.makeCondition([
                    statusId      : 'TASK_WAITING',
                    taskTypeEnumId: 'CASE_STEP_WAIT'
            ]))
            .queryList()
    int checked = 0
    int completed = 0
    waitTasks.each { GenericValue task ->
        if (task.startedDate == null) {
            return
        }
        GenericValue caseValue = EntityQuery.use(delegator).from('Case')
                .where('caseId', task.caseId).queryOne()
        if (caseValue == null) {
            return
        }
        GenericValue workEffort = EntityQuery.use(delegator).from('WorkEffort')
                .where('workEffortId', task.caseId).queryOne()
        if (workEffort != null &&
                ['CASE_RESOLVED', 'CASE_CLOSED', 'CASE_CANCELLED'].contains(workEffort.currentStatusId)) {
            return
        }
        GenericValue wait = EntityQuery.use(delegator).from('CaseStepWait')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : caseValue.caseTypeId,
                        definitionVersion: task.definitionVersion,
                        stageSeq         : task.stageSeq,
                        stepSeq          : task.stepSeq
                ])).queryOne()
        long minutes = (wait?.timeoutDuration ?: 0) as long
        if (minutes <= 0) {
            return
        }
        checked++
        Timestamp timeoutAt = new Timestamp(task.startedDate.time + (minutes * 60L * 1000L))
        if (now.before(timeoutAt)) {
            return
        }
        Map result = dispatcher.runSync('completeCaseTask', [
                userLogin    : serviceUser,
                caseTaskId   : task.caseTaskId,
                caseId       : task.caseId,
                outcomeEnumId: wait.timeoutOutcomeEnumId ?: 'CASE_OUT_TIMEOUT',
                notes        : "Wait timeout after ${minutes} minute(s)"
        ])
        if (ServiceUtil.isError(result)) {
            Debug.logWarning("Wait timeout completion failed for ${task.caseTaskId}: " +
                    ServiceUtil.getErrorMessage(result), 'org.apache.ofbiz.casemgmt.flow.CaseWaitServices')
        } else {
            completed++
        }
    }
    return successMap([checked: checked, completed: completed])
}

private Map checkDraft(String caseTypeId, Object requestedVersion) {
    GenericValue caseType = EntityQuery.use(delegator).from('CaseType')
            .where('caseTypeId', caseTypeId).queryOne()
    if (caseType == null) {
        return [error: "Case type not found: $caseTypeId".toString()]
    }
    if (caseType.statusId != 'CASE_TYPE_DRAFT') {
        return [error: "Case type is not a draft: $caseTypeId".toString()]
    }
    long version = (requestedVersion ?: caseType.definitionVersion ?: 1) as long
    if (version != ((caseType.definitionVersion ?: 1) as long)) {
        return [error: "Definition version mismatch: $version".toString()]
    }
    return [caseTypeId: caseType.caseTypeId, definitionVersion: version]
}

private boolean enumExists(String enumTypeId, String enumId) {
    return EntityQuery.use(delegator).from('Enumeration')
            .where(EntityCondition.makeCondition([
                    enumTypeId: enumTypeId,
                    enumId    : enumId
            ])).queryOne() != null
}

private Map successMap(Map values) {
    Map result = ServiceUtil.returnSuccess()
    result.putAll(values)
    return result
}

private Map errorMap(String message) {
    return ServiceUtil.returnError(message)
}
