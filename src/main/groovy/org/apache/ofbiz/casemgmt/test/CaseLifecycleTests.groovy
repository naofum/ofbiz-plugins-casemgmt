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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Phase 1 case lifecycle tests.
 */
class CaseLifecycleTests extends OFBizTestCase {

    private static final List<String> OPEN_TASK_STATUSES = [
            'TASK_CREATED', 'TASK_ASSIGNED', 'TASK_IN_PROGRESS', 'TASK_WAITING'
    ]

    CaseLifecycleTests(String name) {
        super(name)
    }

    private GenericValue system() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String createCase(String description = 'Test case', String caseTypeId = 'PILOT_INQUIRY') {
        Map result = dispatcher.runSync('createCase', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                customerPartyId : 'TEST_CASEMGMT_CUST',
                description     : description
        ])
        ok(result)
        assert result.caseId
        return result.caseId
    }

    private Map completeFirstTask(String caseId) {
        GenericValue task = firstOpenTask(caseId)
        assert task
        Map result = dispatcher.runSync('completeCaseTask', [
                userLogin    : system(),
                caseTaskId   : task.caseTaskId,
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        return result
    }

    private String caseStatus(String caseId) {
        GenericValue workEffort = from('WorkEffort').where('workEffortId', caseId).queryOne()
        return workEffort?.currentStatusId
    }

    private GenericValue firstOpenTask(String caseId) {
        return from('CaseTask').where('caseId', caseId).queryList()
                .find { GenericValue task -> OPEN_TASK_STATUSES.contains(task.statusId) }
    }

    void testCreateCase() {
        String caseId = createCase('Create case test')
        GenericValue caseValue = from('Case').where('caseId', caseId).queryOne()
        assert caseValue
        assert (caseValue.currentStageSeq as double) == 1.0
        assert (caseValue.currentStageVisitSeq as double) == 1.0
        assert (caseValue.currentStepSeq as double) == 1.0
        assert (caseValue.definitionVersion as long) == 1L

        GenericValue workEffort = from('WorkEffort').where('workEffortId', caseId).queryOne()
        assert workEffort.workEffortTypeId == 'CASE'
        assert workEffort.currentStatusId == 'CASE_NEW'

        List<GenericValue> tasks = from('CaseTask').where('caseId', caseId).queryList()
        assert tasks.size() == 1
        assert tasks[0].statusId == 'TASK_CREATED'
        assert tasks[0].workEffortId
        assert (tasks[0].definitionVersion as long) == 1L
        assert (tasks[0].stageVisitSeq as long) == 1L
        assert tasks[0].requiredIndicator == 'Y'
        assert tasks[0].stepName == 'Triage'

        GenericValue stage = from('CaseStageInstance')
                .where('caseId', caseId, 'stageSeq', 1L, 'visitSeq', 1L)
                .queryOne()
        assert stage
        assert stage.statusId == 'CASE_STAGE_OPEN'
    }

    void testGetCase() {
        String caseId = createCase('Get case test')
        Map result = dispatcher.runSync('getCase', [userLogin: system(), caseId: caseId])
        ok(result)
        assert result.case.caseId == caseId
        assert result.caseHeader.caseId == caseId
        assert result.case.statusId == 'CASE_NEW'
        assert result.tasks.size() == 1
        assert result.history.size() >= 2
    }

    void testListCases() {
        String caseId = createCase('List cases test')
        Map result = dispatcher.runSync('listCases', [userLogin: system()])
        ok(result)
        assert result.cases.any { Map caseMap -> caseMap.caseId == caseId }
    }

    void testCompleteTaskAdvancesStage() {
        String caseId = createCase('Advance stage test')
        GenericValue task = firstOpenTask(caseId)
        assert task

        Map result = dispatcher.runSync('completeCaseTask', [
                userLogin   : system(),
                caseTaskId  : task.caseTaskId,
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        ok(result)
        assert (result.currentStageSeq as double) == 2.0
        assert (result.currentStageVisitSeq as double) == 1.0
        assert caseStatus(caseId) == 'CASE_IN_PROGRESS'

        List<GenericValue> nextTasks = from('CaseTask')
                .where('caseId', caseId, 'stageSeq', 2L, 'stageVisitSeq', 1L)
                .queryList()
        assert nextTasks.size() == 1
        assert nextTasks[0].statusId == 'TASK_CREATED'
        assert (nextTasks[0].definitionVersion as long) == 1L
        assert (nextTasks[0].stageVisitSeq as long) == 1L
    }

    void testCompleteAllStagesResolvesCase() {
        String caseId = createCase('Resolve all stages test')
        int guard = 0
        while (caseStatus(caseId) != 'CASE_RESOLVED' && guard++ < 10) {
            GenericValue task = firstOpenTask(caseId)
            if (task == null) {
                break
            }
            Map result = dispatcher.runSync('completeCaseTask', [
                    userLogin   : system(),
                    caseTaskId  : task.caseTaskId,
                    outcomeEnumId: 'CASE_OUT_COMPLETE'
            ])
            ok(result)
        }
        assert caseStatus(caseId) == 'CASE_RESOLVED'
        GenericValue caseValue = from('Case').where('caseId', caseId).queryOne()
        assert (caseValue.currentStageSeq as double) == 0.0
        assert (caseValue.currentStageVisitSeq as double) == 0.0
    }

    void testCloseCase() {
        String caseId = createCase('Close case test')
        Map result = dispatcher.runSync('closeCase', [
                userLogin    : system(),
                caseId       : caseId,
                resolutionId : 'TEST_RESOLVED'
        ])
        ok(result)
        assert result.statusId == 'CASE_CLOSED'
        assert caseStatus(caseId) == 'CASE_CLOSED'

        List<GenericValue> openTasks = from('CaseTask').where('caseId', caseId).queryList()
                .findAll { GenericValue task -> OPEN_TASK_STATUSES.contains(task.statusId) }
        assert openTasks.isEmpty()
    }

    void testExplainCaseState() {
        String caseId = createCase('Explain case test')
        Map result = dispatcher.runSync('explainCaseState', [userLogin: system(), caseId: caseId])
        ok(result)
        assert result.statusId == 'CASE_NEW'
        assert (result.currentStageSeq as double) == 1.0
        assert (result.currentStageVisitSeq as double) == 1.0
        assert result.openTaskCount == 1
        assert result.blockedReasons.size() == 1
        assert result.nextSteps.size() == 1
    }

    void testValidateCaseWorkEffortTree() {
        String caseId = createCase('Validate tree test')
        Map result = dispatcher.runSync('validateCaseWorkEffortTree', [
                userLogin    : system(),
                workEffortId : caseId
        ])
        ok(result)
        assert result.valid
        assert result.errors.isEmpty()
    }

    void testExitConditionBlocksStageAdvance() {
        String caseId = createCase('Exit condition test', 'TEST_EXIT_FALSE')
        Map result = completeFirstTask(caseId)
        ok(result)
        assert result.advanced == false
        assert result.exitCondition == false

        GenericValue caseValue = from('Case').where('caseId', caseId).queryOne()
        assert (caseValue.currentStageSeq as double) == 1.0
        assert (caseValue.currentStageVisitSeq as double) == 1.0

        List<GenericValue> events = from('CaseEvent')
                .where('caseId', caseId, 'eventTypeEnumId', 'CASE_FLOW_CONDITION')
                .queryList()
        assert events.any { GenericValue event -> event.conditionResult == 'false' }
    }

    void testTransitionPrioritySelectsHigherPriority() {
        String caseId = createCase('Transition priority test', 'TEST_PRIORITY')
        Map result = completeFirstTask(caseId)
        ok(result)
        assert (result.currentStageSeq as double) == 2.0
    }

    void testTransitionDefaultIsUsedWhenConditionFails() {
        String caseId = createCase('Transition default test', 'TEST_DEFAULT')
        Map result = completeFirstTask(caseId)
        ok(result)
        assert (result.currentStageSeq as double) == 3.0
    }

    void testReworkCreatesNewStageVisit() {
        String caseId = createCase('Rework test', 'TEST_REWORK')
        Map first = completeFirstTask(caseId)
        ok(first)
        assert (first.currentStageSeq as double) == 2.0

        GenericValue reviewTask = firstOpenTask(caseId)
        assert reviewTask
        Map rework = dispatcher.runSync('completeCaseTask', [
                userLogin    : system(),
                caseTaskId   : reviewTask.caseTaskId,
                outcomeEnumId: 'CASE_OUT_REJECT'
        ])
        ok(rework)
        assert (rework.currentStageSeq as double) == 1.0
        assert (rework.currentStageVisitSeq as double) == 2.0

        GenericValue firstVisit = from('CaseStageInstance')
                .where('caseId', caseId, 'stageSeq', 1L, 'visitSeq', 1L)
                .queryOne()
        assert firstVisit.statusId == 'CASE_STAGE_COMPLETED'
        GenericValue secondVisit = from('CaseStageInstance')
                .where('caseId', caseId, 'stageSeq', 1L, 'visitSeq', 2L)
                .queryOne()
        assert secondVisit.statusId == 'CASE_STAGE_OPEN'

        GenericValue reworkTask = firstOpenTask(caseId)
        assert reworkTask
        assert (reworkTask.stageVisitSeq as long) == 2L

        Map second = completeFirstTask(caseId)
        ok(second)
        assert (second.currentStageSeq as double) == 2.0
        assert (second.currentStageVisitSeq as double) == 2.0
    }

    void testDefinitionVersionIsPinnedToCase() {
        String caseId = createCase('Definition version test', 'TEST_EXIT_FALSE')
        GenericValue caseType = from('CaseType').where('caseTypeId', 'TEST_EXIT_FALSE').queryOne()
        long originalVersion = caseType.definitionVersion as long
        try {
            caseType.definitionVersion = originalVersion + 1
            caseType.store()

            Map result = completeFirstTask(caseId)
            ok(result)
            GenericValue caseValue = from('Case').where('caseId', caseId).queryOne()
            assert (caseValue.definitionVersion as long) == originalVersion
            assert result.exitCondition == false
            assert (caseValue.currentStageSeq as double) == 1.0
        } finally {
            caseType.definitionVersion = originalVersion
            caseType.store()
        }
    }

    void testServiceAndDecisionStepsAutoAdvance() {
        String caseId = createCase('Auto service/decision test', 'TEST_AUTO')
        GenericValue caseValue = from('Case').where('caseId', caseId).queryOne()
        assert (caseValue.currentStageSeq as double) == 2.0

        List<GenericValue> tasks = from('CaseTask').where('caseId', caseId).orderBy('stageSeq', 'stepSeq').queryList()
        assert tasks.size() == 3
        assert tasks[0].statusId == 'TASK_COMPLETED'
        assert tasks[1].statusId == 'TASK_COMPLETED'
        assert tasks[2].statusId == 'TASK_CREATED'
        assert tasks[2].stageSeq == 2

        GenericValue serviceOutput = from('CaseVariable')
                .where('caseId', caseId, 'variableName', 'test.value').queryOne()
        assert serviceOutput
        assert serviceOutput.valueTypeEnumId == 'CASE_VAR_STRING'
        assert serviceOutput.valueText == 'service-output'
        GenericValue decisionOutput = from('CaseVariable')
                .where('caseId', caseId, 'variableName', 'test.received').queryOne()
        assert decisionOutput
        assert decisionOutput.valueText == 'service-output'
    }

    void testWaitStepAndSignal() {
        String caseId = createCase('Wait step test', 'TEST_WAIT')
        GenericValue waitTask = firstOpenTask(caseId)
        assert waitTask
        assert waitTask.statusId == 'TASK_WAITING'

        Map result = dispatcher.runSync('signalCaseTask', [
                userLogin    : system(),
                caseTaskId   : waitTask.caseTaskId,
                idempotencyKey: "wait-step-signal-${caseId}",
                signalName   : 'employee-ready',
                payload      : 'external signal',
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        ok(result)
        assert !result.idempotent
        assert (result.currentStageSeq as double) == 2.0
        assert (result.currentStageVisitSeq as double) == 1.0

        GenericValue nextTask = firstOpenTask(caseId)
        assert nextTask
        assert nextTask.stageSeq == 2
        assert nextTask.statusId == 'TASK_CREATED'
    }

    void testWaitSignalNameValidation() {
        String caseId = createCase('Wait signal name test', 'TEST_WAIT')
        GenericValue waitTask = firstOpenTask(caseId)
        assert waitTask
        Map wrongSignal = dispatcher.runSync('signalCaseTask', [
                userLogin    : system(),
                caseTaskId   : waitTask.caseTaskId,
                idempotencyKey: "wrong-signal-${caseId}",
                signalName   : 'unexpected-signal',
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        assert ServiceUtil.isError(wrongSignal)
        assert from('CaseTask').where('caseTaskId', waitTask.caseTaskId).queryOne().statusId == 'TASK_WAITING'

        Map missingSignal = dispatcher.runSync('signalCaseTask', [
                userLogin    : system(),
                caseTaskId   : waitTask.caseTaskId,
                idempotencyKey: "missing-signal-${caseId}",
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        assert ServiceUtil.isError(missingSignal)
    }

    void testWaitTimeoutCompletesTask() {
        String caseId = createCase('Wait timeout test', 'TEST_WAIT')
        GenericValue waitTask = firstOpenTask(caseId)
        assert waitTask
        waitTask.startedDate = new java.sql.Timestamp(System.currentTimeMillis() - (2L * 60L * 60L * 1000L))
        waitTask.store()

        Map timeoutResult = dispatcher.runSync('processCaseWaitTimeouts', [
                userLogin: system()
        ])
        ok(timeoutResult)
        assert (timeoutResult.completed as int) >= 1

        GenericValue completedTask = from('CaseTask').where('caseTaskId', waitTask.caseTaskId).queryOne()
        assert completedTask.statusId == 'TASK_COMPLETED'
        assert completedTask.outcomeEnumId == 'CASE_OUT_TIMEOUT'
        GenericValue caseValue = from('Case').where('caseId', caseId).queryOne()
        assert (caseValue.currentStageSeq as double) == 2.0
    }

    void testDuplicateTaskOperationsAreRejected() {
        String caseId = createCase('Duplicate completion test')
        GenericValue task = firstOpenTask(caseId)
        assert task
        Map first = dispatcher.runSync('completeCaseTask', [
                userLogin    : system(),
                caseTaskId   : task.caseTaskId,
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        ok(first)
        Map second = dispatcher.runSync('completeCaseTask', [
                userLogin    : system(),
                caseTaskId   : task.caseTaskId,
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        assert ServiceUtil.isError(second)

        String waitCaseId = createCase('Duplicate signal test', 'TEST_WAIT')
        GenericValue waitTask = firstOpenTask(waitCaseId)
        assert waitTask
        String signalKey = "duplicate-signal-${waitCaseId}"
        Map firstSignal = dispatcher.runSync('signalCaseTask', [
                userLogin    : system(),
                caseTaskId   : waitTask.caseTaskId,
                idempotencyKey: signalKey,
                signalName   : 'employee-ready',
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        ok(firstSignal)
        assert !firstSignal.idempotent
        Map secondSignal = dispatcher.runSync('signalCaseTask', [
                userLogin    : system(),
                caseTaskId   : waitTask.caseTaskId,
                idempotencyKey: signalKey,
                signalName   : 'employee-ready',
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        ok(secondSignal)
        assert secondSignal.idempotent
        assert from('CaseEvent').where('idempotencyKey', signalKey).queryList().size() == 1
        Map thirdSignal = dispatcher.runSync('signalCaseTask', [
                userLogin    : system(),
                caseTaskId   : waitTask.caseTaskId,
                idempotencyKey: "duplicate-signal-other-${waitCaseId}",
                signalName   : 'employee-ready',
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        assert ServiceUtil.isError(thirdSignal)

        String otherWaitCaseId = createCase('Idempotency key conflict test', 'TEST_WAIT')
        GenericValue otherWaitTask = firstOpenTask(otherWaitCaseId)
        assert otherWaitTask
        Map conflict = dispatcher.runSync('signalCaseTask', [
                userLogin    : system(),
                caseTaskId   : otherWaitTask.caseTaskId,
                idempotencyKey: signalKey,
                signalName   : 'employee-ready',
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        assert ServiceUtil.isError(conflict)
    }

    void testCaseVersionCompareAndSet() {
        String caseId = createCase('CAS test')
        GenericValue caseValue = from('Case').where('caseId', caseId).queryOne()
        long version = (caseValue.version ?: 0) as long
        EntityCondition expected = EntityCondition.makeCondition([caseId: caseId, version: version])
        int first = delegator.storeByCondition('Case', [version: version + 1], expected)
        int second = delegator.storeByCondition('Case', [version: version + 1], expected)
        assert first == 1
        assert second == 0
    }

    void testStageEntryServiceRuns() {
        String caseId = createCase('Entry service test', 'TEST_ENTRY')
        List<GenericValue> events = from('CaseEvent')
                .where('caseId', caseId, 'eventTypeEnumId', 'CASE_FLOW_ENTRY')
                .queryList()
        assert events.size() == 1
        assert events[0].conditionResult == 'true'
    }

    void testSubCaseCompletesParent() {
        String parentCaseId = createCase('Sub-case test', 'TEST_SUBCASE')
        GenericValue parentTask = from('CaseTask')
                .where('caseId', parentCaseId, 'taskTypeEnumId', 'CASE_STEP_SUB_CASE')
                .queryOne()
        assert parentTask
        assert parentTask.statusId == 'TASK_WAITING'
        assert parentTask.subCaseId

        String childCaseId = parentTask.subCaseId
        GenericValue childCase = from('Case').where('caseId', childCaseId).queryOne()
        assert childCase
        assert childCase.parentCaseId == parentCaseId

        GenericValue childTask = from('CaseTask').where('caseId', childCaseId).queryOne()
        assert childTask
        assert childTask.statusId == 'TASK_CREATED'

        Map childResult = dispatcher.runSync('completeCaseTask', [
                userLogin    : system(),
                caseTaskId   : childTask.caseTaskId,
                outcomeEnumId: 'CASE_OUT_COMPLETE'
        ])
        ok(childResult)
        assert caseStatus(childCaseId) == 'CASE_RESOLVED'

        GenericValue completedParentTask = from('CaseTask').where('caseTaskId', parentTask.caseTaskId).queryOne()
        assert completedParentTask.statusId == 'TASK_COMPLETED'
        GenericValue parentCase = from('Case').where('caseId', parentCaseId).queryOne()
        assert (parentCase.currentStageSeq as double) == 2.0
    }

    void testSubCaseUsesStepSpecificType() {
        String parentCaseId = createCase('Step-level sub-case test', 'TEST_SUBCASE_STEP')
        GenericValue parentTask = from('CaseTask')
                .where('caseId', parentCaseId, 'taskTypeEnumId', 'CASE_STEP_SUB_CASE')
                .queryOne()
        assert parentTask
        assert parentTask.subCaseId
        GenericValue childCase = from('Case').where('caseId', parentTask.subCaseId).queryOne()
        assert childCase
        assert childCase.caseTypeId == 'TEST_CHILD2'
    }

    void testParallelSubCaseCompletion() {
        String parentCaseId = createCase('Parallel sub-case test', 'TEST_SUB_PARALLEL')
        List<GenericValue> parentTasks = from('CaseTask')
                .where('caseId', parentCaseId, 'stageSeq', 1L)
                .orderBy('stepSeq')
                .queryList()
        assert parentTasks.size() == 2
        assert parentTasks.every { GenericValue task ->
            task.taskTypeEnumId == 'CASE_STEP_SUB_CASE' && task.statusId == 'TASK_WAITING' && task.subCaseId
        }
        List<String> childTaskIds = parentTasks.collect { GenericValue parentTask ->
            GenericValue childTask = from('CaseTask')
                    .where('caseId', parentTask.subCaseId)
                    .queryList()
                    .find { GenericValue task -> OPEN_TASK_STATUSES.contains(task.statusId) }
            assert childTask
            childTask.caseTaskId as String
        }

        GenericValue testUser = system()
        CountDownLatch ready = new CountDownLatch(2)
        CountDownLatch start = new CountDownLatch(1)
        List<Map> results = Collections.synchronizedList([])
        List<Throwable> failures = Collections.synchronizedList([])
        List<Thread> threads = []
        childTaskIds.each { String childTaskId ->
            Thread thread = new Thread({
                try {
                    ready.countDown()
                    start.await(10, TimeUnit.SECONDS)
                    Map result = dispatcher.runSync('completeCaseTask', [
                            userLogin    : testUser,
                            caseTaskId   : childTaskId,
                            outcomeEnumId: 'CASE_OUT_COMPLETE'
                    ])
                    results << result
                } catch (Throwable e) {
                    failures << e
                }
            } as Runnable)
            thread.start()
            threads << thread
        }
        assert ready.await(10, TimeUnit.SECONDS)
        start.countDown()
        threads.each { Thread thread -> thread.join(30000L) }

        assert failures.isEmpty()
        assert results.size() == 2
        assert results.every { Map result -> ServiceUtil.isSuccess(result) },
                results.collect { Map result -> ServiceUtil.getErrorMessage(result) }.join('; ')

        GenericValue parentCase = from('Case').where('caseId', parentCaseId).queryOne()
        assert (parentCase.currentStageSeq as double) == 2.0
        List<GenericValue> completedParentTasks = from('CaseTask')
                .where('caseId', parentCaseId, 'taskTypeEnumId', 'CASE_STEP_SUB_CASE')
                .queryList()
        assert completedParentTasks.size() == 2
        assert completedParentTasks.every { GenericValue task -> task.statusId == 'TASK_COMPLETED' }
        assert from('CaseTask').where('caseId', parentCaseId, 'stageSeq', 2L).queryList().size() == 1
        assert from('CaseEvent')
                .where('caseId', parentCaseId, 'eventTypeEnumId', 'CASE_SUBCASE_DONE')
                .queryList().size() == 2
        List<GenericValue> stageEvents = from('CaseEvent')
                .where('caseId', parentCaseId, 'eventTypeEnumId', 'CASE_STAGE_COMPLETED')
                .queryList()
                .findAll { GenericValue event -> (event.fromStageSeq as long) == 1L }
        assert stageEvents.size() == 1
    }

    void testGenericWorkEffortIsGuarded() {
        boolean failed = false
        try {
            Map result = dispatcher.runSync('createWorkEffort', [
                    userLogin       : system(),
                    workEffortId    : 'TEST_GUARD_CASE',
                    workEffortTypeId: 'CASE',
                    workEffortName  : 'Guard test case',
                    currentStatusId : 'CASE_NEW'
            ])
            failed = ServiceUtil.isError(result)
        } catch (Exception e) {
            failed = true
        }
        assert failed
        assert from('WorkEffort').where('workEffortId', 'TEST_GUARD_CASE').queryOne() == null
    }
}
