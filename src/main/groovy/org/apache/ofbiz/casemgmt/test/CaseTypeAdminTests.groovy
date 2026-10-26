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
 * Phase 2 case type authoring, validation and publishing tests.
 */
class CaseTypeAdminTests extends OFBizTestCase {

    CaseTypeAdminTests(String name) {
        super(name)
    }

    private GenericValue system() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private void createDraft(String caseTypeId) {
        Map result = dispatcher.runSync('createCaseTypeDraft', [
                userLogin   : system(),
                caseTypeId  : caseTypeId,
                caseTypeName: caseTypeId,
                description : 'Case type admin test'
        ])
        ok(result)
    }

    private void addStageAndStep(String caseTypeId, long version, long stageSeq, String stageName, String stepName) {
        Map stage = dispatcher.runSync('createCaseStageDef', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: version,
                stageSeq        : stageSeq,
                stageName       : stageName,
                stageTypeEnumId : 'CASE_STAGE_PRIMARY',
                autoAdvance     : 'N'
        ])
        ok(stage)
        Map step = dispatcher.runSync('createCaseStepDef', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: version,
                stageSeq        : stageSeq,
                stepSeq         : 1,
                stepName        : stepName,
                stepTypeEnumId  : 'CASE_STEP_HUMAN_TASK',
                requiredIndicator: 'Y',
                sequenceNum     : 1
        ])
        ok(step)
    }

    void testServiceInputAndOutputMappings() {
        String caseTypeId = 'TSM' + System.currentTimeMillis()
        createDraft(caseTypeId)
        addStageAndStep(caseTypeId, 1, 1, 'Start', 'Work')

        Map param = dispatcher.runSync('createCaseStepServiceParam', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: 1,
                stageSeq        : 1,
                stepSeq         : 1,
                paramName       : 'amount',
                sourceTypeEnumId: 'CASE_SVC_SRC_LITERAL',
                sourceValue     : '100',
                requiredIndicator: 'Y'
        ])
        ok(param)
        assert from('CaseStepServiceParam').where('caseTypeId', caseTypeId,
                'definitionVersion', 1L, 'stageSeq', 1L, 'stepSeq', 1L).queryOne()

        Map output = dispatcher.runSync('createCaseStepServiceOutput', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: 1,
                stageSeq        : 1,
                stepSeq         : 1,
                outputName      : 'approvedAmount',
                variableName    : 'credit.limit',
                valueTypeEnumId : 'CASE_VAR_NUMBER',
                scopeEnumId     : 'CASE_VAR_SCOPE_CASE',
                requiredIndicator: 'Y'
        ])
        ok(output)
        assert from('CaseStepServiceOutput').where('caseTypeId', caseTypeId,
                'definitionVersion', 1L, 'stageSeq', 1L, 'stepSeq', 1L).queryOne()

        ok(dispatcher.runSync('deleteCaseStepServiceParam', [
                userLogin: system(), caseTypeId: caseTypeId, definitionVersion: 1L,
                stageSeq: 1L, stepSeq: 1L, paramSeq: param.paramSeq
        ]))
        ok(dispatcher.runSync('deleteCaseStepServiceOutput', [
                userLogin: system(), caseTypeId: caseTypeId, definitionVersion: 1L,
                stageSeq: 1L, stepSeq: 1L, outputSeq: output.outputSeq
        ]))
        assert from('CaseStepServiceParam').where('caseTypeId', caseTypeId,
                'definitionVersion', 1L, 'stageSeq', 1L, 'stepSeq', 1L).queryOne() == null
        assert from('CaseStepServiceOutput').where('caseTypeId', caseTypeId,
                'definitionVersion', 1L, 'stageSeq', 1L, 'stepSeq', 1L).queryOne() == null

        ok(dispatcher.runSync('createCaseStepServiceParam', [
                userLogin: system(), caseTypeId: caseTypeId, definitionVersion: 1L,
                stageSeq: 1L, stepSeq: 1L, paramName: 'amount',
                sourceTypeEnumId: 'CASE_SVC_SRC_LITERAL', sourceValue: '200'
        ]))
        ok(dispatcher.runSync('createCaseStepServiceOutput', [
                userLogin: system(), caseTypeId: caseTypeId, definitionVersion: 1L,
                stageSeq: 1L, stepSeq: 1L, outputName: 'approvedAmount',
                variableName: 'credit.limit', valueTypeEnumId: 'CASE_VAR_NUMBER',
                scopeEnumId: 'CASE_VAR_SCOPE_CASE'
        ]))
        ok(dispatcher.runSync('deleteCaseTypeDraft', [userLogin: system(), caseTypeId: caseTypeId]))
        assert from('CaseType').where('caseTypeId', caseTypeId).queryOne() == null
        assert from('CaseStepServiceParam').where('caseTypeId', caseTypeId).queryOne() == null
        assert from('CaseStepServiceOutput').where('caseTypeId', caseTypeId).queryOne() == null
    }

    void testValidateAndPublish() {
        String caseTypeId = 'TEST_ADMIN_TYPE'
        createDraft(caseTypeId)
        addStageAndStep(caseTypeId, 1, 1, 'Start', 'Work')

        Map validation = dispatcher.runSync('validateCaseTypeDefinition', [
                userLogin : system(),
                caseTypeId: caseTypeId
        ])
        ok(validation)
        assert validation.valid
        assert validation.errors.isEmpty()

        Map publish = dispatcher.runSync('publishCaseTypeDefinition', [
                userLogin : system(),
                caseTypeId: caseTypeId
        ])
        ok(publish)
        GenericValue published = from('CaseType').where('caseTypeId', caseTypeId).queryOne()
        assert published.statusId == 'CASE_TYPE_ACTIVE'
        assert (published.activeDefinitionVersion as long) == 1L
    }

    void testInvalidDefinitionCannotBePublished() {
        String caseTypeId = 'TEST_BAD_TYPE'
        createDraft(caseTypeId)
        Map stage = dispatcher.runSync('createCaseStageDef', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: 1,
                stageSeq        : 1,
                stageName       : 'Empty stage',
                autoAdvance     : 'N'
        ])
        ok(stage)

        Map validation = dispatcher.runSync('validateCaseTypeDefinition', [
                userLogin : system(),
                caseTypeId: caseTypeId
        ])
        ok(validation)
        assert !validation.valid
        assert !validation.errors.isEmpty()

        Map publish = dispatcher.runSync('publishCaseTypeDefinition', [
                userLogin : system(),
                caseTypeId: caseTypeId
        ])
        assert ServiceUtil.isError(publish)
    }

    void testStageStepAndTransitionCrud() {
        String caseTypeId = 'TEST_CRUD_TYPE'
        createDraft(caseTypeId)
        addStageAndStep(caseTypeId, 1, 1, 'First', 'First step')
        addStageAndStep(caseTypeId, 1, 2, 'Second', 'Second step')

        ok(dispatcher.runSync('updateCaseStageDef', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: 1,
                stageSeq        : 1,
                stageName       : 'First updated'
        ]))
        ok(dispatcher.runSync('moveCaseStageDef', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: 1,
                stageSeq        : 2,
                direction       : 'up'
        ]))
        List<GenericValue> stages = from('CaseStageDef')
                .where('caseTypeId', caseTypeId, 'definitionVersion', 1L)
                .orderBy('stageSeq').queryList()
        assert stages[0].stageName == 'Second'
        assert stages[1].stageName == 'First updated'
        assert from('CaseStepDef').where('caseTypeId', caseTypeId, 'definitionVersion', 1L,
                'stageSeq', 1L).queryOne().stepName == 'Second step'

        ok(dispatcher.runSync('createCaseStepDef', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepName         : 'Second step B'
        ]))
        ok(dispatcher.runSync('moveCaseStepDef', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 2,
                direction        : 'up'
        ]))
        List<GenericValue> steps = from('CaseStepDef')
                .where('caseTypeId', caseTypeId, 'definitionVersion', 1L, 'stageSeq', 1L)
                .orderBy('stepSeq').queryList()
        assert steps[0].stepName == 'Second step B'

        ok(dispatcher.runSync('createCaseStepTransition', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1,
                outcomeEnumId    : 'CASE_OUT_COMPLETE',
                toStageSeq       : 2,
                toStepSeq        : 1,
                priority         : 1,
                isDefault        : 'Y'
        ]))
        GenericValue transition = from('CaseStepTransition')
                .where('caseTypeId', caseTypeId, 'definitionVersion', 1L,
                        'stageSeq', 1L, 'stepSeq', 1L).queryOne()
        assert transition
        ok(dispatcher.runSync('updateCaseStepTransition', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1,
                outcomeEnumId    : 'CASE_OUT_COMPLETE',
                transitionSeq    : transition.transitionSeq,
                priority         : 9
        ]))
        GenericValue updatedTransition = from('CaseStepTransition').where(
                org.apache.ofbiz.entity.condition.EntityCondition.makeCondition([
                        caseTypeId       : caseTypeId,
                        definitionVersion: 1L,
                        stageSeq         : 1L,
                        stepSeq          : 1L,
                        outcomeEnumId    : 'CASE_OUT_COMPLETE'
                ])).queryOne()
        assert updatedTransition
        assert (updatedTransition.priority as long) == 9L
        ok(dispatcher.runSync('deleteCaseStepTransition', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1,
                outcomeEnumId    : 'CASE_OUT_COMPLETE',
                transitionSeq    : transition.transitionSeq
        ]))
        assert from('CaseStepTransition').where('caseTypeId', caseTypeId).queryList().isEmpty()

        ok(dispatcher.runSync('deleteCaseStepDef', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1
        ]))
        ok(dispatcher.runSync('deleteCaseStageDef', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1
        ]))
    }

    void testFieldMappingCrudAndVersionDiff() {
        String caseTypeId = 'TEST_DIFF_TYPE'
        createDraft(caseTypeId)
        addStageAndStep(caseTypeId, 1, 1, 'Start', 'Work')
        Map field = dispatcher.runSync('createCaseFieldMapping', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1,
                fieldName        : 'description',
                label            : 'Description',
                fieldTypeEnumId  : 'CASE_FIELD_TEXTAREA',
                requiredIndicator: 'Y'
        ])
        ok(field)
        ok(dispatcher.runSync('publishCaseTypeDefinition', [userLogin: system(), caseTypeId: caseTypeId]))
        ok(dispatcher.runSync('cloneCaseTypeDefinition', [userLogin: system(), caseTypeId: caseTypeId]))

        ok(dispatcher.runSync('updateCaseFieldMapping', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 2,
                stageSeq         : 1,
                stepSeq          : 1,
                fieldSeq         : field.fieldSeq,
                label            : 'Complaint description',
                requiredIndicator: 'N'
        ]))
        ok(dispatcher.runSync('updateCaseStageDef', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 2,
                stageSeq         : 1,
                stageName        : 'Start v2'
        ]))
        GenericValue v1Field = from('CaseFieldMapping').where('caseTypeId', caseTypeId,
                'definitionVersion', 1L, 'stageSeq', 1L, 'stepSeq', 1L, 'fieldSeq', field.fieldSeq).queryOne()
        GenericValue v2Field = from('CaseFieldMapping').where('caseTypeId', caseTypeId,
                'definitionVersion', 2L, 'stageSeq', 1L, 'stepSeq', 1L, 'fieldSeq', field.fieldSeq).queryOne()
        assert v1Field && v1Field.label == 'Description'
        assert v2Field && v2Field.label == 'Complaint description'

        Map diff = dispatcher.runSync('getCaseTypeDefinitionDiff', [
                userLogin : system(),
                caseTypeId: caseTypeId
        ])
        ok(diff)
        assert diff.fromVersion == 1L
        assert diff.toVersion == 2L
        assert diff.hasChanges
        assert !diff.stageChanges.isEmpty()
        assert !diff.fieldChanges.isEmpty()
        assert diff.fieldChanges[0].change == 'CHANGED'

        ok(dispatcher.runSync('deleteCaseFieldMapping', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 2,
                stageSeq         : 1,
                stepSeq          : 1,
                fieldSeq         : field.fieldSeq
        ]))
        assert from('CaseFieldMapping').where('caseTypeId', caseTypeId, 'definitionVersion', 2L)
                .queryList().isEmpty()
    }

    void testLocalizedFieldLabelCrud() {
        String caseTypeId = 'TFL' + System.currentTimeMillis()
        createDraft(caseTypeId)
        Map field = dispatcher.runSync('createCaseFieldMapping', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1,
                fieldName        : 'description',
                label            : 'Description'
        ])
        ok(field)

        ok(dispatcher.runSync('saveCaseFieldLabel', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1,
                fieldSeq         : field.fieldSeq,
                labelLocale      : 'ja',
                label            : '内容',
                description      : 'Japanese label'
        ]))
        GenericValue japanese = from('CaseFieldLabel').where('caseTypeId', caseTypeId,
                'definitionVersion', 1L, 'stageSeq', 1L, 'stepSeq', 1L,
                'fieldSeq', field.fieldSeq, 'locale', 'ja').queryOne()
        assert japanese
        assert japanese.label == '内容'

        ok(dispatcher.runSync('saveCaseFieldLabel', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1,
                fieldSeq         : field.fieldSeq,
                labelLocale      : 'ja-JP',
                label            : '内容（日本）'
        ]))
        GenericValue japan = from('CaseFieldLabel').where('caseTypeId', caseTypeId,
                'definitionVersion', 1L, 'stageSeq', 1L, 'stepSeq', 1L,
                'fieldSeq', field.fieldSeq, 'locale', 'ja_JP').queryOne()
        assert japan
        assert japan.label == '内容（日本）'

        ok(dispatcher.runSync('deleteCaseFieldLabel', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1,
                fieldSeq         : field.fieldSeq,
                labelLocale      : 'ja'
        ]))
        assert from('CaseFieldLabel').where('caseTypeId', caseTypeId,
                'fieldSeq', field.fieldSeq, 'locale', 'ja').queryOne() == null
        ok(dispatcher.runSync('deleteCaseTypeDraft', [userLogin: system(), caseTypeId: caseTypeId]))
    }

    void testWaitAndSubCaseStepSettings() {
        String caseTypeId = 'TWS' + System.currentTimeMillis()
        createDraft(caseTypeId)
        addStageAndStep(caseTypeId, 1, 1, 'Start', 'Wait step')

        ok(dispatcher.runSync('updateCaseStepDef', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1,
                stepTypeEnumId   : 'CASE_STEP_WAIT'
        ]))
        Map save = dispatcher.runSync('saveCaseStepWait', [
                userLogin          : system(),
                caseTypeId         : caseTypeId,
                definitionVersion  : 1,
                stageSeq           : 1,
                stepSeq            : 1,
                signalName         : 'ready',
                timeoutDuration    : 30,
                timeoutOutcomeEnumId: 'CASE_OUT_TIMEOUT',
                description        : 'Wait settings test'
        ])
        ok(save)
        GenericValue wait = from('CaseStepWait').where('caseTypeId', caseTypeId,
                'definitionVersion', 1L, 'stageSeq', 1L, 'stepSeq', 1L).queryOne()
        assert wait
        assert wait.signalName == 'ready'
        assert (wait.timeoutDuration as long) == 30L

        Map get = dispatcher.runSync('getCaseStepWait', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1
        ])
        ok(get)
        assert get.stepWait.signalName == 'ready'

        Map validation = dispatcher.runSync('validateCaseTypeDefinition', [
                userLogin : system(),
                caseTypeId: caseTypeId
        ])
        ok(validation)
        assert validation.valid

        ok(dispatcher.runSync('deleteCaseStepWait', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1
        ]))
        assert from('CaseStepWait').where('caseTypeId', caseTypeId).queryOne() == null
        Map invalid = dispatcher.runSync('validateCaseTypeDefinition', [
                userLogin : system(),
                caseTypeId: caseTypeId
        ])
        ok(invalid)
        assert !invalid.valid

        ok(dispatcher.runSync('updateCaseStepDef', [
                userLogin        : system(),
                caseTypeId       : caseTypeId,
                definitionVersion: 1,
                stageSeq         : 1,
                stepSeq          : 1,
                stepTypeEnumId   : 'CASE_STEP_SUB_CASE',
                subCaseTypeId    : 'TEST_CHILD'
        ]))
        assert from('CaseStepDef').where('caseTypeId', caseTypeId, 'definitionVersion', 1L,
                'stageSeq', 1L, 'stepSeq', 1L).queryOne().subCaseTypeId == 'TEST_CHILD'

        Map createdWait = dispatcher.runSync('createCaseStepDef', [
                userLogin          : system(),
                caseTypeId         : caseTypeId,
                definitionVersion  : 1,
                stageSeq           : 1,
                stepSeq            : 2,
                stepName           : 'Created wait',
                stepTypeEnumId     : 'CASE_STEP_WAIT',
                signalName         : 'created-ready',
                timeoutDuration    : 15,
                timeoutOutcomeEnumId: 'CASE_OUT_TIMEOUT'
        ])
        ok(createdWait)
        GenericValue createdWaitConfig = from('CaseStepWait').where('caseTypeId', caseTypeId,
                'definitionVersion', 1L, 'stageSeq', 1L, 'stepSeq', 2L).queryOne()
        assert createdWaitConfig
        assert createdWaitConfig.signalName == 'created-ready'
        assert (createdWaitConfig.timeoutDuration as long) == 15L

        ok(dispatcher.runSync('deleteCaseTypeDraft', [userLogin: system(), caseTypeId: caseTypeId]))
    }

    void testCloneAndVersionPinning() {
        String caseTypeId = 'TEST_VERSION_TYPE'
        createDraft(caseTypeId)
        addStageAndStep(caseTypeId, 1, 1, 'Start', 'Work')
        ok(dispatcher.runSync('publishCaseTypeDefinition', [userLogin: system(), caseTypeId: caseTypeId]))

        Map firstCase = dispatcher.runSync('createCase', [
                userLogin : system(),
                caseTypeId: caseTypeId
        ])
        ok(firstCase)
        String firstCaseId = firstCase.caseId
        assert (from('Case').where('caseId', firstCaseId).queryOne().definitionVersion as long) == 1L

        Map clone = dispatcher.runSync('cloneCaseTypeDefinition', [
                userLogin : system(),
                caseTypeId: caseTypeId
        ])
        ok(clone)
        assert (clone.definitionVersion as long) == 2L
        Map update = dispatcher.runSync('updateCaseStageDef', [
                userLogin       : system(),
                caseTypeId      : caseTypeId,
                definitionVersion: 2,
                stageSeq        : 1,
                stageName       : 'Start v2'
        ])
        ok(update)
        ok(dispatcher.runSync('validateCaseTypeDefinition', [
                userLogin: system(), caseTypeId: caseTypeId, definitionVersion: 2
        ]))
        ok(dispatcher.runSync('publishCaseTypeDefinition', [userLogin: system(), caseTypeId: caseTypeId]))

        GenericValue caseType = from('CaseType').where('caseTypeId', caseTypeId).queryOne()
        assert (caseType.activeDefinitionVersion as long) == 2L
        assert (from('Case').where('caseId', firstCaseId).queryOne().definitionVersion as long) == 1L

        Map secondCase = dispatcher.runSync('createCase', [
                userLogin : system(),
                caseTypeId: caseTypeId
        ])
        ok(secondCase)
        assert (from('Case').where('caseId', secondCase.caseId).queryOne().definitionVersion as long) == 2L
    }
}
