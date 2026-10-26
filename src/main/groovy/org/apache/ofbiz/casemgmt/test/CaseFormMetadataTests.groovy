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
 * Start/task form metadata and form submission tests.
 */
class CaseFormMetadataTests extends OFBizTestCase {

    CaseFormMetadataTests(String name) {
        super(name)
    }

    private GenericValue system() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private void createDraft(String caseTypeId) {
        ok(dispatcher.runSync('createCaseTypeDraft', [
                userLogin   : system(),
                caseTypeId  : caseTypeId,
                caseTypeName: caseTypeId,
                description : 'Form metadata test'
        ]))
        ok(dispatcher.runSync('createCaseStageDef', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: 1,
                stageSeq        : 1,
                stageName       : 'Start',
                stageTypeEnumId : 'CASE_STAGE_PRIMARY',
                autoAdvance     : 'N'
        ]))
        ok(dispatcher.runSync('createCaseStepDef', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: 1,
                stageSeq        : 1,
                stepSeq         : 1,
                stepName        : 'Work',
                stepTypeEnumId  : 'CASE_STEP_HUMAN_TASK',
                requiredIndicator: 'Y',
                sequenceNum     : 1
        ]))
    }

    void testStartFormMetadataAndSubmission() {
        Map metadata = dispatcher.runSync('getCaseTypeStartForm', [
                userLogin : system(),
                caseTypeId: 'TEST_FORM'
        ])
        ok(metadata)
        assert metadata.startForm.formName == 'TestStartCaseForm'
        assert metadata.startForm.formTarget == 'StartCaseSubmit'
        assert metadata.startForm.startServiceName == 'casemgmtTestStartForm'

        Map result = dispatcher.runSync('submitStartCase', [
                userLogin : system(),
                caseTypeId: 'TEST_FORM',
                formValues: [description: 'From the start form']
        ])
        ok(result)
        assert result.caseId
        assert result.linkedContexts == 1
        GenericValue caseValue = from('Case').where('caseId', result.caseId).queryOne()
        assert caseValue.description == 'Started from test form'
        assert caseValue.customerPartyId == 'TEST_CASEMGMT_CUST'
        GenericValue context = from('CaseContext').where(
                'caseId', result.caseId, 'entityName', 'PartyRole').queryOne()
        assert context
        assert context.contextTypeEnumId == 'CASE_CTX_PRIMARY'
        assert context.pkValue == null
        List<GenericValue> keys = from('CaseContextKey').where(
                'caseId', result.caseId, 'contextSeq', context.contextSeq).orderBy('keySeq').queryList()
        assert keys.size() == 2
        assert keys[0].keyName == 'partyId'
        assert keys[0].keyValue == 'TEST_AGENT1'
        assert keys[1].keyName == 'roleTypeId'
        assert keys[1].keyValue == 'CAL_OWNER'
    }

    void testTaskFormMetadataAndSubmission() {
        Map caseResult = dispatcher.runSync('createCase', [
                userLogin : system(),
                caseTypeId: 'TEST_FORM'
        ])
        ok(caseResult)
        GenericValue task = from('CaseTask')
                .where('caseId', caseResult.caseId, 'statusId', 'TASK_CREATED').queryOne()
        assert task

        Map form = dispatcher.runSync('getCaseTaskForm', [
                userLogin : system(),
                caseTaskId: task.caseTaskId
        ])
        ok(form)
        assert form.formMode == 'STEP_FORM'
        assert form.stepForm.formName == 'TestTaskCaseForm'
        assert form.fields.isEmpty()

        Map submitted = dispatcher.runSync('submitCaseTaskForm', [
                userLogin : system(),
                caseTaskId: task.caseTaskId,
                formValues: [taskComment: 'approved', outcomeEnumId: 'CASE_OUT_COMPLETE']
        ])
        ok(submitted)
        assert submitted.formServiceName == 'casemgmtTestTaskForm'
        GenericValue completedTask = from('CaseTask').where('caseTaskId', task.caseTaskId).queryOne()
        assert completedTask.statusId == 'TASK_COMPLETED'
        GenericValue event = from('CaseEvent')
                .where('caseId', caseResult.caseId, 'detail', 'task-form:approved').queryOne()
        assert event
        assert event.refEntityName == 'CaseTask'
        assert event.refPkValue == task.caseTaskId
    }

    void testStandardFormInheritanceAndReuse() {
        Map metadata = dispatcher.runSync('getCaseStepForm', [
                userLogin : system(),
                caseTypeId: 'TEST_STANDARD_FORM'
        ])
        ok(metadata)
        assert metadata.stepForm.formName == 'CaseStandardPartyNoteForm'
        assert metadata.stepForm.formTarget == 'TaskFormSubmit'
        assert metadata.stepForm.completeServiceName == 'createPartyNote'

        String draftId = 'TSF' + System.currentTimeMillis()
        createDraft(draftId)
        Map saved = dispatcher.runSync('saveCaseStepForm', [
                userLogin        : system(),
                caseTypeId       : draftId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1,
                formLocation     : 'component://casemgmt/widget/CasemgmtForms.xml',
                formName         : 'CaseStandardPartyNoteForm',
                formTarget       : 'TaskFormSubmit',
                completeServiceName: 'createPartyNote'
        ])
        ok(saved)
        ok(dispatcher.runSync('deleteCaseTypeDraft', [userLogin: system(), caseTypeId: draftId]))

        Map caseResult = dispatcher.runSync('createCase', [
                userLogin       : system(),
                caseTypeId      : 'TEST_STANDARD_FORM',
                customerPartyId : 'TEST_CASEMGMT_CUST'
        ])
        ok(caseResult)
        GenericValue task = from('CaseTask')
                .where('caseId', caseResult.caseId, 'statusId', 'TASK_CREATED').queryOne()
        assert task

        Map form = dispatcher.runSync('getCaseTaskForm', [
                userLogin : system(),
                caseTaskId: task.caseTaskId
        ])
        ok(form)
        assert form.formMode == 'STEP_FORM'
        assert form.stepForm.formName == 'CaseStandardPartyNoteForm'

        String noteName = "std-form-note-${System.currentTimeMillis()}"
        Map submitted = dispatcher.runSync('submitCaseTaskForm', [
                userLogin : system(),
                caseTaskId: task.caseTaskId,
                formValues: [
                        partyId : 'TEST_CASEMGMT_CUST',
                        noteName: noteName,
                        note    : 'Created through the standard AddPartyNote form'
                ]
        ])
        ok(submitted)
        assert submitted.formServiceName == 'createPartyNote'

        GenericValue noteData = from('NoteData').where('noteName', noteName).queryOne()
        assert noteData
        assert from('PartyNote').where('partyId', 'TEST_CASEMGMT_CUST',
                'noteId', noteData.noteId).queryOne()
        assert from('CaseTask').where('caseTaskId', task.caseTaskId).queryOne().statusId == 'TASK_COMPLETED'
    }

    void testFormMetadataCrudAndTargetValidation() {
        String caseTypeId = 'TSF' + System.currentTimeMillis()
        createDraft(caseTypeId)

        Map invalid = dispatcher.runSync('saveCaseTypeStartForm', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: 1,
                formLocation    : 'component://casemgmt/widget/CasemgmtForms.xml',
                formName        : 'CreateCase',
                formTarget      : 'StartCaseSubmit'
        ])
        assert ServiceUtil.isError(invalid)

        ok(dispatcher.runSync('saveCaseTypeStartForm', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: 1,
                formLocation    : 'component://casemgmt/widget/CasemgmtForms.xml',
                formName        : 'TestStartCaseForm',
                formTarget      : 'StartCaseSubmit',
                startServiceName: 'casemgmtTestStartForm',
                submitLabel     : 'Start'
        ]))
        ok(dispatcher.runSync('saveCaseStepForm', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: 1,
                stageSeq        : 1,
                stepSeq         : 1,
                formLocation    : 'component://casemgmt/widget/CasemgmtForms.xml',
                formName        : 'TestTaskCaseForm',
                formTarget      : 'TaskFormSubmit',
                completeServiceName: 'casemgmtTestTaskForm'
        ]))
        Map startMetadata = dispatcher.runSync('getCaseTypeStartForm', [
                userLogin : system(),
                caseTypeId: caseTypeId,
                definitionVersion: 1
        ])
        ok(startMetadata)
        assert startMetadata.startForm.formName == 'TestStartCaseForm'
        Map stepMetadata = dispatcher.runSync('getCaseStepForm', [
                userLogin : system(),
                caseTypeId: caseTypeId,
                definitionVersion: 1,
                stageSeq: 1,
                stepSeq: 1
        ])
        ok(stepMetadata)
        assert stepMetadata.stepForm.formName == 'TestTaskCaseForm'

        ok(dispatcher.runSync('deleteCaseTypeStartForm', [
                userLogin: system(), caseTypeId: caseTypeId, definitionVersion: 1
        ]))
        ok(dispatcher.runSync('deleteCaseStepForm', [
                userLogin: system(), caseTypeId: caseTypeId, definitionVersion: 1,
                stageSeq: 1, stepSeq: 1
        ]))
        assert from('CaseTypeStartForm').where('caseTypeId', caseTypeId).queryOne() == null
        assert from('CaseStepForm').where('caseTypeId', caseTypeId).queryOne() == null
        ok(dispatcher.runSync('deleteCaseTypeDraft', [userLogin: system(), caseTypeId: caseTypeId]))
    }
}
