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

import org.apache.ofbiz.casemgmt.assignment.CaseAssignmentHelper
import org.apache.ofbiz.casemgmt.workeffort.CaseWorkEffortContext
import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery

/**
 * Shared runtime helpers for case creation and stage advancement.
 */
final class CaseRuntimeHelper {

    private static final List<String> OPEN_TASK_STATUSES = [
            'TASK_CREATED', 'TASK_ASSIGNED', 'TASK_IN_PROGRESS', 'TASK_WAITING'
    ]

    private CaseRuntimeHelper() {
    }

    static void createCaseWorkEffort(Delegator delegator, String caseId, String caseNumber,
                                     String description, Double priority, String userLoginId, Timestamp now,
                                     String parentWorkEffortId = null) {
        Map<String, Object> values = [
                workEffortId          : caseId,
                workEffortTypeId      : 'CASE',
                workEffortParentId    : parentWorkEffortId,
                workEffortName        : caseNumber,
                currentStatusId       : 'CASE_NEW',
                priority              : priority ?: 3,
                estimatedStartDate    : now,
                description           : description,
                createdDate           : now,
                lastModifiedDate      : now,
                createdByUserLogin    : userLoginId,
                lastModifiedByUserLogin: userLoginId
        ]
        CaseWorkEffortContext.runAuthorized {
            delegator.create('WorkEffort', values)
        }
    }

    static String createSubCase(Delegator delegator, String parentCaseId, String childCaseTypeId,
                                String ownerPartyId, String customerPartyId, String userLoginId, Timestamp now) {
        GenericValue childType = EntityQuery.use(delegator)
                .from('CaseType')
                .where('caseTypeId', childCaseTypeId)
                .queryOne()
        if (childType == null || childType.statusId != 'CASE_TYPE_ACTIVE') {
            return null
        }
        String childCaseId = delegator.getNextSeqId('WorkEffort')
        String childCaseNumber = 'CASE-' + childCaseId
        long definitionVersion = (childType.definitionVersion ?: 1) as long

        createCaseWorkEffort(delegator, childCaseId, childCaseNumber,
                "Sub-case of $parentCaseId", 3D, userLoginId, now, parentCaseId)
        delegator.create('Case', [
                caseId               : childCaseId,
                caseTypeId           : childCaseTypeId,
                caseNumber           : childCaseNumber,
                currentStageSeq      : 1,
                currentStageVisitSeq : 1,
                currentStepSeq       : 1,
                ownerPartyId         : ownerPartyId,
                customerPartyId      : customerPartyId,
                priority             : 3,
                openedDate           : now,
                accessPolicyId       : childType.accessPolicyId,
                definitionVersion    : definitionVersion,
                version              : 1,
                parentCaseId         : parentCaseId,
                description          : "Sub-case of $parentCaseId"
        ])
        return childCaseId
    }

    static String createAssignmentWorkEffort(Delegator delegator, String caseId, GenericValue step,
                                             String userLoginId, Timestamp now) {
        String workEffortId = delegator.getNextSeqId('WorkEffort')
        Map<String, Object> values = [
                workEffortId          : workEffortId,
                workEffortTypeId      : 'CASE_ASSIGNMENT',
                workEffortParentId    : caseId,
                workEffortName        : step.stepName,
                currentStatusId       : 'TASK_CREATED',
                priority              : 3,
                estimatedStartDate    : now,
                description           : step.description,
                createdDate           : now,
                lastModifiedDate      : now,
                createdByUserLogin    : userLoginId,
                lastModifiedByUserLogin: userLoginId
        ]
        CaseWorkEffortContext.runAuthorized {
            delegator.create('WorkEffort', values)
        }
        return workEffortId
    }

    static void createStageInstance(Delegator delegator, String caseId, long stageSeq, long visitSeq,
                                    String userLoginId, Timestamp now) {
        delegator.create('CaseStageInstance', [
                caseId             : caseId,
                stageSeq           : stageSeq,
                visitSeq           : visitSeq,
                enteredDate        : now,
                statusId           : 'CASE_STAGE_OPEN',
                enteredByUserLogin : userLoginId
        ])
    }

    static void createStageTasks(Delegator delegator, String caseId, String caseTypeId,
                                 long definitionVersion, long stageSeq, long stageVisitSeq,
                                 String ownerPartyId, String userLoginId, Timestamp now) {
        List<GenericValue> steps = EntityQuery.use(delegator)
                .from('CaseStepDef')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : caseTypeId,
                        definitionVersion: definitionVersion,
                        stageSeq         : stageSeq
                ]))
                .orderBy('sequenceNum')
                .queryList()

        steps.each { GenericValue step ->
            String workEffortId = null
            String assignedPartyId = null
            String assignedQueueId = null
            boolean humanTask = step.stepTypeEnumId == 'CASE_STEP_HUMAN_TASK'
            if (humanTask) {
                workEffortId = createAssignmentWorkEffort(delegator, caseId, step, userLoginId, now)
                Map assignment = CaseAssignmentHelper.resolveAssignment(delegator, step, ownerPartyId, now)
                assignedPartyId = assignment.assignedPartyId
                assignedQueueId = assignment.assignedQueueId
            }
            String taskStatusId
            if (step.stepTypeEnumId == 'CASE_STEP_WAIT' || step.stepTypeEnumId == 'CASE_STEP_SUB_CASE') {
                taskStatusId = 'TASK_WAITING'
            } else if (assignedPartyId) {
                taskStatusId = 'TASK_ASSIGNED'
            } else {
                taskStatusId = 'TASK_CREATED'
            }
            String caseTaskId = delegator.getNextSeqId('CaseTask')
            delegator.create('CaseTask', [
                    caseTaskId       : caseTaskId,
                    caseId           : caseId,
                    definitionVersion: definitionVersion,
                    stageSeq         : stageSeq,
                    stageVisitSeq    : stageVisitSeq,
                    stepSeq          : step.stepSeq,
                    stepName         : step.stepName,
                    requiredIndicator: step.requiredIndicator ?: 'Y',
                    taskTypeEnumId   : step.stepTypeEnumId,
                    workEffortId     : workEffortId,
                    assignedPartyId  : assignedPartyId,
                    assignedQueueId  : assignedQueueId,
                    statusId         : taskStatusId,
                    startedDate      : step.stepTypeEnumId == 'CASE_STEP_WAIT' ? now : null,
                    priority         : 3
            ])
            if (workEffortId && assignedPartyId) {
                delegator.create('WorkEffortPartyAssignment', [
                        workEffortId   : workEffortId,
                        partyId        : assignedPartyId,
                        roleTypeId     : 'CAL_OWNER',
                        fromDate       : now,
                        statusId       : 'PRTYASGN_ASSIGNED',
                        statusDateTime : now
                ])
            }
            createCaseEvent(delegator, caseId, 'CASE_TASK_CREATED', userLoginId, now, [
                    toStageSeq   : stageSeq,
                    stageVisitSeq: stageVisitSeq,
                    toStepSeq    : step.stepSeq,
                    detail       : "Task created: ${step.stepName}".toString()
            ])
        }
    }

    static void createCaseEvent(Delegator delegator, String caseId, String eventTypeEnumId,
                                String userLoginId, Timestamp now, Map<String, Object> extra = [:]) {
        Map<String, Object> event = [
                caseEventId      : delegator.getNextSeqId('CaseEvent'),
                caseId           : caseId,
                eventTypeEnumId  : eventTypeEnumId,
                eventDate        : now,
                actorUserLoginId : userLoginId,
                triggerTypeEnumId: 'CASE_TRIG_SERVICE',
                engineVersion    : '1.0'
        ]
        extra.each { String key, Object value ->
            if (value != null) {
                event[key] = value
            }
        }
        delegator.create('CaseEvent', event)
    }

    static void changeCaseStatus(Delegator delegator, String caseId, String newStatusId,
                                 String userLoginId, Timestamp now, String reason) {
        GenericValue workEffort = EntityQuery.use(delegator)
                .from('WorkEffort')
                .where('workEffortId', caseId)
                .queryOne()
        if (workEffort == null) {
            throw new IllegalArgumentException("Case WorkEffort not found: $caseId")
        }
        if (workEffort.currentStatusId == newStatusId) {
            return
        }
        GenericValue validChange = EntityQuery.use(delegator)
                .from('StatusValidChange')
                .where(EntityCondition.makeCondition([
                        statusId  : workEffort.currentStatusId,
                        statusIdTo: newStatusId
                ]))
                .queryOne()
        if (validChange == null) {
            throw new IllegalStateException(
                    "Invalid case status transition: ${workEffort.currentStatusId} -> $newStatusId")
        }
        CaseWorkEffortContext.runAuthorized {
            workEffort.currentStatusId = newStatusId
            workEffort.lastStatusUpdate = now
            workEffort.store()
        }
        delegator.create('WorkEffortStatus', [
                workEffortId     : caseId,
                statusId         : newStatusId,
                statusDatetime   : now,
                setByUserLogin   : userLoginId,
                reason           : reason
        ])
    }

    static void updateAssignmentWorkEffort(Delegator delegator, String workEffortId, String statusId,
                                           String userLoginId, Timestamp now) {
        GenericValue workEffort = EntityQuery.use(delegator)
                .from('WorkEffort')
                .where('workEffortId', workEffortId)
                .queryOne()
        if (workEffort == null || workEffort.currentStatusId == statusId) {
            return
        }
        GenericValue validChange = EntityQuery.use(delegator)
                .from('StatusValidChange')
                .where(EntityCondition.makeCondition([statusId: workEffort.currentStatusId, statusIdTo: statusId]))
                .queryOne()
        if (validChange == null) {
            throw new IllegalStateException(
                    "Invalid task status transition: ${workEffort.currentStatusId} -> $statusId")
        }
        CaseWorkEffortContext.runAuthorized {
            workEffort.currentStatusId = statusId
            workEffort.lastStatusUpdate = now
            if (statusId == 'TASK_COMPLETED') {
                workEffort.actualCompletionDate = now
            }
            workEffort.store()
        }
        delegator.create('WorkEffortStatus', [
                workEffortId     : workEffortId,
                statusId         : statusId,
                statusDatetime   : now,
                setByUserLogin   : userLoginId,
                reason           : 'Case task updated'
        ])
    }

    static List<GenericValue> getOpenTasks(Delegator delegator, String caseId) {
        return EntityQuery.use(delegator)
                .from('CaseTask')
                .where('caseId', caseId)
                .queryList()
                .findAll { GenericValue task -> OPEN_TASK_STATUSES.contains(task.statusId) }
    }

    static List<GenericValue> getOpenTasksForStage(Delegator delegator, String caseId,
                                                   long stageSeq, long stageVisitSeq) {
        return EntityQuery.use(delegator)
                .from('CaseTask')
                .where(EntityCondition.makeCondition([
                        caseId        : caseId,
                        stageSeq      : stageSeq,
                        stageVisitSeq : stageVisitSeq
                ]))
                .queryList()
                .findAll { GenericValue task -> OPEN_TASK_STATUSES.contains(task.statusId) }
    }

    static List<GenericValue> getOpenRequiredTasksForStage(Delegator delegator, String caseId,
                                                           long stageSeq, long stageVisitSeq) {
        return getOpenTasksForStage(delegator, caseId, stageSeq, stageVisitSeq)
                .findAll { GenericValue task -> task.requiredIndicator != 'N' }
    }
}
