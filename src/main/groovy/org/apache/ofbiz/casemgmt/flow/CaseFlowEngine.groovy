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

import org.apache.ofbiz.casemgmt.cases.CaseConcurrencyHelper
import org.apache.ofbiz.casemgmt.cases.CaseRuntimeHelper
import org.apache.ofbiz.casemgmt.cases.CaseServiceMappingHelper
import org.apache.ofbiz.casemgmt.sla.CaseSlaHelper
import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.LocalDispatcher
import org.apache.ofbiz.service.ServiceUtil

/**
 * Generic, deterministic case flow interpreter.
 *
 * <p>The engine contains no case-type-specific branches. It reads the
 * definition version pinned on the case, evaluates optional stage exit and
 * transition conditions, executes service/decision steps when a stage is
 * marked auto-advance, and advances the case when all required tasks in the
 * current stage are complete.</p>
 */
final class CaseFlowEngine {

    private CaseFlowEngine() {
    }

    /**
     * Enter a stage: run its entry service, create the stage instance and
     * create the stage's tasks.
     */
    static Map startStage(Delegator delegator, LocalDispatcher dispatcher, GenericValue userLogin,
                          String caseId, long definitionVersion, long stageSeq, long stageVisitSeq,
                          String ownerPartyId, Timestamp now) {
        String loginId = userLogin?.userLoginId ?: 'system'
        GenericValue caseValue = EntityQuery.use(delegator)
                .from('Case')
                .where('caseId', caseId)
                .queryOne()
        if (caseValue == null) {
            return errorMap("Case not found: $caseId")
        }
        GenericValue stageDef = EntityQuery.use(delegator)
                .from('CaseStageDef')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : caseValue.caseTypeId,
                        definitionVersion: definitionVersion,
                        stageSeq         : stageSeq
                ]))
                .queryOne()
        if (stageDef == null) {
            return errorMap("Case stage definition not found: ${caseValue.caseTypeId} / $definitionVersion / $stageSeq")
        }

        if (stageDef.entryServiceName) {
            Map entryResult
            try {
                entryResult = dispatcher.runSync(stageDef.entryServiceName, [
                        caseId           : caseId,
                        caseTypeId       : caseValue.caseTypeId,
                        definitionVersion: definitionVersion,
                        stageSeq         : stageSeq,
                        stageVisitSeq    : stageVisitSeq,
                        userLogin        : userLogin
                ])
            } catch (Exception e) {
                return errorMap("Stage entry service failed: ${stageDef.entryServiceName}: ${e.message}")
            }
            boolean entryOk = !ServiceUtil.isError(entryResult)
            if (entryOk && entryResult.conditionResult != null) {
                entryOk = parseCondition(entryResult.conditionResult)
            }
            CaseRuntimeHelper.createCaseEvent(delegator, caseId, 'CASE_FLOW_ENTRY', loginId, now, [
                    toStageSeq    : stageSeq,
                    stageVisitSeq : stageVisitSeq,
                    conditionResult: entryOk.toString(),
                    detail        : "Stage entry service ${stageDef.entryServiceName} = $entryOk".toString()
            ])
            if (!entryOk) {
                return errorMap("Stage entry condition failed: ${stageDef.entryServiceName}")
            }
        }

        CaseRuntimeHelper.createStageInstance(delegator, caseId, stageSeq, stageVisitSeq, loginId, now)
        CaseRuntimeHelper.createStageTasks(delegator, caseId, caseValue.caseTypeId, definitionVersion,
                stageSeq, stageVisitSeq, ownerPartyId, loginId, now)

        Map subCaseResult = createSubCasesForStage(delegator, dispatcher, userLogin, caseValue,
                definitionVersion, stageSeq, stageVisitSeq, ownerPartyId, now)
        if (ServiceUtil.isError(subCaseResult)) {
            return subCaseResult
        }
        return successMap([entered: true, stageSeq: stageSeq, stageVisitSeq: stageVisitSeq])
    }

    static Map onCaseTaskCompleted(Delegator delegator, LocalDispatcher dispatcher, GenericValue userLogin,
                                   String caseId, long definitionVersion, long stageSeq, long stageVisitSeq,
                                   long stepSeq, String outcomeEnumId, Timestamp now) {
        String loginId = userLogin?.userLoginId ?: 'system'
        List<GenericValue> openRequiredTasks = CaseRuntimeHelper.getOpenRequiredTasksForStage(
                delegator, caseId, stageSeq, stageVisitSeq)
        if (!openRequiredTasks.isEmpty()) {
            return successMap([advanced: false, openTaskCount: openRequiredTasks.size()])
        }

        GenericValue caseValue = EntityQuery.use(delegator)
                .from('Case')
                .where('caseId', caseId)
                .queryOne()
        if (caseValue == null) {
            return errorMap("Case not found: $caseId")
        }

        GenericValue stageInstance = EntityQuery.use(delegator)
                .from('CaseStageInstance')
                .where(EntityCondition.makeCondition([
                        caseId  : caseId,
                        stageSeq: stageSeq,
                        visitSeq: stageVisitSeq
                ]))
                .queryOne()
        if (stageInstance == null) {
            return errorMap("Case stage instance not found: $caseId / $stageSeq / $stageVisitSeq")
        }

        GenericValue stageDef = EntityQuery.use(delegator)
                .from('CaseStageDef')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : caseValue.caseTypeId,
                        definitionVersion: definitionVersion,
                        stageSeq         : stageSeq
                ]))
                .queryOne()

        if (stageDef?.exitServiceName) {
            boolean exitCondition = evaluateCondition(dispatcher, stageDef.exitServiceName, [
                    caseId           : caseId,
                    caseTypeId       : caseValue.caseTypeId,
                    definitionVersion: definitionVersion,
                    stageSeq         : stageSeq,
                    stageVisitSeq    : stageVisitSeq,
                    stepSeq          : stepSeq,
                    outcomeEnumId    : outcomeEnumId,
                    userLogin        : userLogin
            ])
            CaseRuntimeHelper.createCaseEvent(delegator, caseId, 'CASE_FLOW_CONDITION',
                    loginId, now, [
                            fromStageSeq   : stageSeq,
                            stageVisitSeq  : stageVisitSeq,
                            fromStepSeq    : stepSeq,
                            conditionResult: exitCondition.toString(),
                            detail         : "Stage exit condition ${stageDef.exitServiceName} = $exitCondition".toString()
                    ])
            if (!exitCondition) {
                return successMap([
                        advanced          : false,
                        exitCondition     : false,
                        blockedByCondition: stageDef.exitServiceName
                ])
            }
        }

        String effectiveOutcome = outcomeEnumId ?: 'CASE_OUT_COMPLETE'
        Map transitionResult = selectTransition(delegator, dispatcher, userLogin, caseValue,
                definitionVersion, stageSeq, stageVisitSeq, stepSeq, effectiveOutcome, now)
        GenericValue transition = transitionResult.transition
        if (!transitionResult.found && transitionResult.hasCandidates) {
            return errorMap("No valid case transition for $caseId / $stageSeq / $stepSeq / $effectiveOutcome")
        }

        stageInstance.completedDate = now
        stageInstance.statusId = 'CASE_STAGE_COMPLETED'
        stageInstance.completedByUserLogin = loginId
        stageInstance.store()
        CaseRuntimeHelper.createCaseEvent(delegator, caseId, 'CASE_STAGE_COMPLETED', loginId, now, [
                fromStageSeq : stageSeq,
                stageVisitSeq: stageVisitSeq,
                fromStepSeq  : stepSeq,
                detail       : "Stage completed: $stageSeq / $stageVisitSeq".toString()
        ])

        Long nextStageSeq = null
        Long nextStepSeq = null
        if (transition != null) {
            if (transition.toStageSeq != null && transition.toStageSeq > 0) {
                nextStageSeq = transition.toStageSeq as Long
                nextStepSeq = (transition.toStepSeq ?: 1) as Long
            }
        } else {
            List<GenericValue> stages = EntityQuery.use(delegator)
                    .from('CaseStageDef')
                    .where(EntityCondition.makeCondition([
                            caseTypeId       : caseValue.caseTypeId,
                            definitionVersion: definitionVersion
                    ]))
                    .orderBy('stageSeq')
                    .queryList()
            GenericValue nextStage = stages.find { GenericValue stage -> stage.stageSeq > stageSeq }
            if (nextStage != null) {
                nextStageSeq = nextStage.stageSeq as Long
                nextStepSeq = 1L
            }
        }

        if (nextStageSeq == null) {
            GenericValue workEffort = EntityQuery.use(delegator)
                    .from('WorkEffort')
                    .where('workEffortId', caseId)
                    .queryOne()
            if (workEffort?.currentStatusId == 'CASE_NEW') {
                CaseRuntimeHelper.changeCaseStatus(delegator, caseId, 'CASE_IN_PROGRESS', loginId, now,
                        'Case started')
            }
            CaseRuntimeHelper.changeCaseStatus(delegator, caseId, 'CASE_RESOLVED', loginId, now,
                    'All stages completed')
            caseValue.currentStageSeq = 0L
            caseValue.currentStageVisitSeq = 0L
            caseValue.currentStepSeq = 0L
            caseValue.version = (caseValue.version ?: 0) + 1
            caseValue.store()
            CaseRuntimeHelper.createCaseEvent(delegator, caseId, 'CASE_STAGE_COMPLETED', loginId, now, [
                    fromStageSeq : stageSeq,
                    stageVisitSeq: stageVisitSeq,
                    fromStepSeq  : stepSeq,
                    detail       : 'No next stage; case resolved'
            ])
            Map slaResult = CaseSlaHelper.completeSlaInstances(delegator, userLogin, caseId, now)
            if (ServiceUtil.isError(slaResult)) {
                return slaResult
            }
            Map parentResult = onSubCaseClosed(delegator, dispatcher, userLogin, caseId, now)
            if (ServiceUtil.isError(parentResult)) {
                return parentResult
            }
            return successMap([advanced: false, resolved: true])
        }

        long nextStageVisitSeq = determineNextVisitSeq(delegator, caseId, nextStageSeq)
        CaseRuntimeHelper.changeCaseStatus(delegator, caseId, 'CASE_IN_PROGRESS', loginId, now,
                'Next stage started')
        caseValue.currentStageSeq = nextStageSeq
        caseValue.currentStageVisitSeq = nextStageVisitSeq
        caseValue.currentStepSeq = nextStepSeq
        caseValue.version = (caseValue.version ?: 0) + 1
        caseValue.store()

        Map stageResult = startStage(delegator, dispatcher, userLogin, caseId, definitionVersion,
                nextStageSeq, nextStageVisitSeq, caseValue.ownerPartyId, now)
        if (ServiceUtil.isError(stageResult)) {
            return stageResult
        }
        CaseRuntimeHelper.createCaseEvent(delegator, caseId, 'CASE_STAGE_ENTERED', loginId, now, [
                fromStageSeq  : stageSeq,
                toStageSeq    : nextStageSeq,
                stageVisitSeq : nextStageVisitSeq,
                toStepSeq     : nextStepSeq,
                detail        : "Stage entered: $nextStageSeq / $nextStageVisitSeq".toString()
        ])
        return successMap([
                advanced            : true,
                currentStageSeq     : nextStageSeq,
                currentStageVisitSeq: nextStageVisitSeq,
                currentStepSeq      : nextStepSeq
        ])
    }

    /**
     * Execute service/decision tasks and empty auto-advance stages until a
     * human task, a wait task, a non-auto stage, or case resolution is reached.
     */
    static Map runAutoAdvance(Delegator delegator, LocalDispatcher dispatcher, GenericValue userLogin,
                              String caseId, Timestamp now) {
        String loginId = userLogin?.userLoginId ?: 'system'
        for (int iteration = 0; iteration < 50; iteration++) {
            GenericValue caseValue = EntityQuery.use(delegator)
                    .from('Case')
                    .where('caseId', caseId)
                    .queryOne()
            if (caseValue == null) {
                return errorMap("Case not found: $caseId")
            }
            GenericValue workEffort = EntityQuery.use(delegator)
                    .from('WorkEffort')
                    .where('workEffortId', caseId)
                    .queryOne()
            if (workEffort == null) {
                return errorMap("Case WorkEffort not found: $caseId")
            }
            if (['CASE_RESOLVED', 'CASE_CLOSED', 'CASE_CANCELLED'].contains(workEffort.currentStatusId)) {
                return successMap([autoAdvanced: false])
            }
            long stageSeq = caseValue.currentStageSeq as long
            long stageVisitSeq = (caseValue.currentStageVisitSeq ?: 1) as long
            if (stageSeq <= 0) {
                return successMap([autoAdvanced: false])
            }
            GenericValue stageDef = EntityQuery.use(delegator)
                    .from('CaseStageDef')
                    .where(EntityCondition.makeCondition([
                            caseTypeId       : caseValue.caseTypeId,
                            definitionVersion: caseValue.definitionVersion,
                            stageSeq         : stageSeq
                    ]))
                    .queryOne()
            if (stageDef?.autoAdvance != 'Y') {
                return successMap([autoAdvanced: false])
            }

            List<GenericValue> openTasks = CaseRuntimeHelper.getOpenTasksForStage(
                    delegator, caseId, stageSeq, stageVisitSeq)
            if (openTasks.isEmpty()) {
                Map advanceResult = onCaseTaskCompleted(delegator, dispatcher, userLogin, caseId,
                        caseValue.definitionVersion as long, stageSeq, stageVisitSeq, 0L, null, now)
                if (ServiceUtil.isError(advanceResult)) {
                    return advanceResult
                }
                if (advanceResult.resolved) {
                    return successMap([autoAdvanced: true, resolved: true])
                }
                if (advanceResult.exitCondition == false) {
                    return successMap([autoAdvanced: false, exitCondition: false])
                }
                if (!advanceResult.advanced) {
                    return successMap([autoAdvanced: false])
                }
                continue
            }

            GenericValue autoTask = openTasks.find { GenericValue task ->
                (task.taskTypeEnumId == 'CASE_STEP_SERVICE' || task.taskTypeEnumId == 'CASE_STEP_DECISION') &&
                        task.statusId == 'TASK_CREATED'
            }
            if (autoTask == null) {
                return successMap([autoAdvanced: false])
            }

            GenericValue stepDef = EntityQuery.use(delegator)
                    .from('CaseStepDef')
                    .where(EntityCondition.makeCondition([
                            caseTypeId       : caseValue.caseTypeId,
                            definitionVersion: caseValue.definitionVersion,
                            stageSeq         : autoTask.stageSeq,
                            stepSeq          : autoTask.stepSeq
                    ]))
                    .queryOne()
            if (stepDef == null || !stepDef.serviceName) {
                return errorMap("Service/decision step has no service: ${autoTask.caseTaskId}")
            }

            Map<String, Object> baseContext = [
                    caseId           : caseId,
                    caseTypeId       : caseValue.caseTypeId,
                    definitionVersion: caseValue.definitionVersion,
                    stageSeq         : autoTask.stageSeq,
                    stageVisitSeq    : autoTask.stageVisitSeq,
                    stepSeq          : autoTask.stepSeq,
                    caseTaskId       : autoTask.caseTaskId,
                    userLogin        : userLogin
            ]
            Map contextResult = CaseServiceMappingHelper.buildServiceContext(
                    delegator, caseValue, autoTask, userLogin, baseContext)
            if (contextResult.error) {
                return errorMap(contextResult.error)
            }
            Map serviceResult
            try {
                serviceResult = dispatcher.runSync(stepDef.serviceName, contextResult.context as Map)
            } catch (Exception e) {
                return errorMap("Step service failed: ${stepDef.serviceName}: ${e.message}")
            }
            if (ServiceUtil.isError(serviceResult)) {
                return serviceResult
            }
            Map outputResult = CaseServiceMappingHelper.saveOutputs(
                    delegator, caseValue, autoTask, serviceResult, now)
            if (outputResult.error) {
                return errorMap(outputResult.error)
            }
            String outcomeEnumId = serviceResult.outcomeEnumId ?: 'CASE_OUT_COMPLETE'
            completeAutoTask(delegator, autoTask, outcomeEnumId, loginId, now)
            Map advanceResult = onCaseTaskCompleted(delegator, dispatcher, userLogin, caseId,
                    caseValue.definitionVersion as long, autoTask.stageSeq as long,
                    autoTask.stageVisitSeq as long, autoTask.stepSeq as long, outcomeEnumId, now)
            if (ServiceUtil.isError(advanceResult)) {
                return advanceResult
            }
            if (advanceResult.resolved) {
                return successMap([autoAdvanced: true, resolved: true])
            }
            if (advanceResult.exitCondition == false) {
                return successMap([autoAdvanced: false, exitCondition: false])
            }
        }
        return errorMap("Auto advance exceeded 50 iterations for case $caseId")
    }

    private static void completeAutoTask(Delegator delegator, GenericValue task, String outcomeEnumId,
                                         String loginId, Timestamp now) {
        task.statusId = 'TASK_COMPLETED'
        task.completedDate = now
        task.completedByUserLogin = loginId
        task.outcomeEnumId = outcomeEnumId
        task.store()
        CaseRuntimeHelper.createCaseEvent(delegator, task.caseId, 'CASE_TASK_COMPLETED', loginId, now, [
                fromStageSeq : task.stageSeq,
                stageVisitSeq: task.stageVisitSeq,
                fromStepSeq  : task.stepSeq,
                detail       : "Auto task completed: ${task.caseTaskId} / $outcomeEnumId".toString()
        ])
    }

    private static Map createSubCasesForStage(Delegator delegator, LocalDispatcher dispatcher,
                                              GenericValue userLogin, GenericValue caseValue,
                                              long definitionVersion, long stageSeq, long stageVisitSeq,
                                              String ownerPartyId, Timestamp now) {
        String loginId = userLogin?.userLoginId ?: 'system'
        List<GenericValue> subCaseTasks = EntityQuery.use(delegator)
                .from('CaseTask')
                .where(EntityCondition.makeCondition([
                        caseId        : caseValue.caseId,
                        stageSeq      : stageSeq,
                        stageVisitSeq : stageVisitSeq,
                        taskTypeEnumId: 'CASE_STEP_SUB_CASE'
                ]))
                .queryList()
                .findAll { GenericValue task -> !task.subCaseId }
        if (subCaseTasks.isEmpty()) {
            return successMap([created: 0])
        }

        GenericValue subCaseTypeAttr = EntityQuery.use(delegator)
                .from('CaseTypeAttr')
                .where(EntityCondition.makeCondition([
                        caseTypeId: caseValue.caseTypeId,
                        attrName  : 'SUB_CASE_TYPE'
                ]))
                .queryOne()
        for (GenericValue task : subCaseTasks) {
            GenericValue stepDef = EntityQuery.use(delegator)
                    .from('CaseStepDef')
                    .where(EntityCondition.makeCondition([
                            caseTypeId       : caseValue.caseTypeId,
                            definitionVersion: definitionVersion,
                            stageSeq         : task.stageSeq,
                            stepSeq          : task.stepSeq
                    ]))
                    .queryOne()
            String childCaseTypeId = stepDef?.subCaseTypeId ?: subCaseTypeAttr?.attrValue
            if (!childCaseTypeId) {
                return errorMap("Sub-case type is not configured for case type ${caseValue.caseTypeId} " +
                        "at step ${task.stageSeq}/${task.stepSeq}")
            }
            String childCaseId = CaseRuntimeHelper.createSubCase(delegator, caseValue.caseId,
                    childCaseTypeId, ownerPartyId, caseValue.customerPartyId, loginId, now)
            if (!childCaseId) {
                return errorMap("Sub-case type is not active: $childCaseTypeId")
            }
            task.subCaseId = childCaseId
            task.store()
            CaseRuntimeHelper.createCaseEvent(delegator, caseValue.caseId, 'CASE_SUBCASE_NEW',
                    loginId, now, [
                            fromStageSeq : stageSeq,
                            stageVisitSeq: stageVisitSeq,
                            fromStepSeq  : task.stepSeq,
                            refEntityName: 'Case',
                            refPkValue   : childCaseId,
                            detail       : "Sub-case created: $childCaseId"
                    ])

            GenericValue childCase = EntityQuery.use(delegator)
                    .from('Case')
                    .where('caseId', childCaseId)
                    .queryOne()
            Map childStageResult = startStage(delegator, dispatcher, userLogin, childCaseId,
                    childCase.definitionVersion as long, 1, 1, ownerPartyId, now)
            if (ServiceUtil.isError(childStageResult)) {
                return childStageResult
            }
            Map childAutoResult = runAutoAdvance(delegator, dispatcher, userLogin, childCaseId, now)
            if (ServiceUtil.isError(childAutoResult)) {
                return childAutoResult
            }
        }
        return successMap([created: subCaseTasks.size()])
    }

    static Map onSubCaseClosed(Delegator delegator, LocalDispatcher dispatcher, GenericValue userLogin,
                               String childCaseId, Timestamp now) {
        String loginId = userLogin?.userLoginId ?: 'system'
        List<GenericValue> allParentTasks = EntityQuery.use(delegator)
                .from('CaseTask')
                .where('subCaseId', childCaseId)
                .queryList()
        if (allParentTasks.isEmpty()) {
            return successMap([completed: false])
        }
        Map<String, List<GenericValue>> tasksByCase = allParentTasks.groupBy { GenericValue task ->
            task.caseId as String
        }
        int completedTasks = 0
        for (Map.Entry<String, List<GenericValue>> entry : tasksByCase.entrySet()) {
            String parentCaseId = entry.key
            List<GenericValue> waitingTasks = entry.value.findAll { GenericValue task ->
                task.statusId == 'TASK_WAITING'
            }
            if (waitingTasks.isEmpty()) {
                continue
            }
            if (!lockCaseWithRetry(delegator, parentCaseId)) {
                return errorMap("Parent case was updated by another operation; reload and retry: $parentCaseId")
            }
            GenericValue parentCase = EntityQuery.use(delegator)
                    .from('Case')
                    .where('caseId', parentCaseId)
                    .queryOne()
            if (parentCase == null) {
                return errorMap("Parent case not found: $parentCaseId")
            }
            long currentStageSeq = (parentCase.currentStageSeq ?: 0L) as long
            long currentStageVisitSeq = (parentCase.currentStageVisitSeq ?: 0L) as long
            List<GenericValue> currentTasks = EntityQuery.use(delegator)
                    .from('CaseTask')
                    .where(EntityCondition.makeCondition([
                            caseId    : parentCaseId,
                            subCaseId : childCaseId,
                            statusId  : 'TASK_WAITING'
                    ]))
                    .queryList()
            List<GenericValue> updatedTasks = []
            for (GenericValue parentTask : currentTasks) {
                if ((parentTask.stageSeq as long) != currentStageSeq ||
                        (parentTask.stageVisitSeq as long) != currentStageVisitSeq) {
                    continue
                }
                if (!CaseConcurrencyHelper.updateTask(delegator, parentTask.caseTaskId,
                        'TASK_WAITING', [
                                statusId            : 'TASK_COMPLETED',
                                completedDate       : now,
                                completedByUserLogin: loginId,
                                outcomeEnumId       : 'CASE_OUT_COMPLETE'
                        ])) {
                    continue
                }
                parentTask.statusId = 'TASK_COMPLETED'
                parentTask.completedDate = now
                parentTask.completedByUserLogin = loginId
                parentTask.outcomeEnumId = 'CASE_OUT_COMPLETE'
                updatedTasks << parentTask
                completedTasks++
                CaseRuntimeHelper.createCaseEvent(delegator, parentTask.caseId, 'CASE_TASK_COMPLETED',
                        loginId, now, [
                                fromStageSeq : parentTask.stageSeq,
                                stageVisitSeq: parentTask.stageVisitSeq,
                                fromStepSeq  : parentTask.stepSeq,
                                refEntityName: 'Case',
                                refPkValue   : childCaseId,
                                detail       : "Sub-case completed: $childCaseId"
                        ])
                CaseRuntimeHelper.createCaseEvent(delegator, parentTask.caseId, 'CASE_SUBCASE_DONE',
                        loginId, now, [
                                fromStageSeq : parentTask.stageSeq,
                                stageVisitSeq: parentTask.stageVisitSeq,
                                fromStepSeq  : parentTask.stepSeq,
                                refEntityName: 'Case',
                                refPkValue   : childCaseId,
                                detail       : "Sub-case completed: $childCaseId"
                        ])
            }
            if (updatedTasks.isEmpty()) {
                continue
            }
            GenericValue transitionTask = updatedTasks.first()
            Map parentResult = onCaseTaskCompleted(delegator, dispatcher, userLogin, parentCaseId,
                    transitionTask.definitionVersion as long, transitionTask.stageSeq as long,
                    transitionTask.stageVisitSeq as long, transitionTask.stepSeq as long,
                    'CASE_OUT_COMPLETE', now)
            if (ServiceUtil.isError(parentResult)) {
                return parentResult
            }
            Map parentAutoResult = runAutoAdvance(delegator, dispatcher, userLogin, parentCaseId, now)
            if (ServiceUtil.isError(parentAutoResult)) {
                return parentAutoResult
            }
        }
        return successMap([completed: completedTasks > 0, completedTasks: completedTasks])
    }

    private static boolean lockCaseWithRetry(Delegator delegator, String caseId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            if (CaseConcurrencyHelper.lockCase(delegator, caseId)) {
                return true
            }
            try {
                Thread.sleep(25L)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private static Map selectTransition(Delegator delegator, LocalDispatcher dispatcher,
                                        GenericValue userLogin, GenericValue caseValue,
                                        long definitionVersion, long stageSeq, long stageVisitSeq,
                                        long stepSeq, String outcomeEnumId, Timestamp now) {
        List<GenericValue> transitions = EntityQuery.use(delegator)
                .from('CaseStepTransition')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : caseValue.caseTypeId,
                        definitionVersion: definitionVersion,
                        stageSeq         : stageSeq,
                        stepSeq          : stepSeq,
                        outcomeEnumId    : outcomeEnumId
                ]))
                .queryList()

        if (transitions.isEmpty()) {
            return [found: false, hasCandidates: false, transition: null]
        }

        transitions.sort { GenericValue left, GenericValue right ->
            int byPriority = (right.priority ?: 0) <=> (left.priority ?: 0)
            if (byPriority != 0) {
                return byPriority
            }
            int byDefault = (left.isDefault == 'Y' ? 1 : 0) <=> (right.isDefault == 'Y' ? 1 : 0)
            if (byDefault != 0) {
                return byDefault
            }
            return (left.transitionSeq ?: 0) <=> (right.transitionSeq ?: 0)
        }

        String loginId = userLogin?.userLoginId ?: 'system'
        GenericValue defaultTransition = null
        for (GenericValue transition : transitions) {
            if (transition.isDefault == 'Y' && defaultTransition == null) {
                defaultTransition = transition
            }
            if (transition.conditionServiceName) {
                boolean condition = evaluateCondition(dispatcher, transition.conditionServiceName, [
                        caseId           : caseValue.caseId,
                        caseTypeId       : caseValue.caseTypeId,
                        definitionVersion: definitionVersion,
                        stageSeq         : stageSeq,
                        stageVisitSeq    : stageVisitSeq,
                        stepSeq          : stepSeq,
                        outcomeEnumId    : outcomeEnumId,
                        transitionSeq    : transition.transitionSeq,
                        userLogin        : userLogin
                ])
                CaseRuntimeHelper.createCaseEvent(delegator, caseValue.caseId, 'CASE_FLOW_CONDITION',
                        loginId, now, [
                                fromStageSeq   : stageSeq,
                                stageVisitSeq  : stageVisitSeq,
                                fromStepSeq    : stepSeq,
                                conditionResult: condition.toString(),
                                detail         : "Transition condition ${transition.conditionServiceName} = $condition".toString()
                        ])
                if (!condition) {
                    continue
                }
            }
            return [found: true, hasCandidates: true, transition: transition]
        }

        if (defaultTransition != null && !defaultTransition.conditionServiceName) {
            return [found: true, hasCandidates: true, transition: defaultTransition]
        }

        CaseRuntimeHelper.createCaseEvent(delegator, caseValue.caseId, 'CASE_FLOW_NO_TRANS',
                loginId, now, [
                        fromStageSeq : stageSeq,
                        stageVisitSeq: stageVisitSeq,
                        fromStepSeq  : stepSeq,
                        detail       : "No transition for outcome $outcomeEnumId".toString()
                ])
        return [found: false, hasCandidates: true, transition: null]
    }

    private static long determineNextVisitSeq(Delegator delegator, String caseId, long nextStageSeq) {
        List<GenericValue> instances = EntityQuery.use(delegator)
                .from('CaseStageInstance')
                .where(EntityCondition.makeCondition([caseId: caseId, stageSeq: nextStageSeq]))
                .queryList()
        long maxVisit = 0L
        instances.each { GenericValue instance ->
            long visit = instance.visitSeq ?: 0L
            if (visit > maxVisit) {
                maxVisit = visit
            }
        }
        return maxVisit + 1
    }

    private static boolean evaluateCondition(LocalDispatcher dispatcher, String serviceName, Map context) {
        if (!serviceName) {
            return true
        }
        try {
            Map result = dispatcher.runSync(serviceName, context)
            if (ServiceUtil.isError(result)) {
                return false
            }
            Object value = result.conditionResult
            if (value == null) {
                value = result.success
            }
            return parseCondition(value)
        } catch (Exception ignored) {
            return false
        }
    }

    private static boolean parseCondition(Object value) {
        if (value instanceof Boolean) {
            return value
        }
        return value != null && Boolean.parseBoolean(value.toString())
    }

    private static Map successMap(Map values) {
        Map result = ServiceUtil.returnSuccess()
        result.putAll(values)
        return result
    }

    private static Map errorMap(String message) {
        return ServiceUtil.returnError(message)
    }
}
