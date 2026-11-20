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
package org.apache.ofbiz.casemgmt.casetype

import java.security.MessageDigest
import java.sql.Timestamp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.ofbiz.base.component.ComponentConfig
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.UtilXml
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.transaction.TransactionUtil
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil
import org.w3c.dom.Document
import org.w3c.dom.Element

Map createCaseTypeDraft() {
    String caseTypeId = parameters.caseTypeId
    if (!caseTypeId) {
        return errorMap('caseTypeId is required')
    }
    if (from('CaseType').where('caseTypeId', caseTypeId).queryOne() != null) {
        return errorMap("Case type already exists: $caseTypeId")
    }
    Timestamp now = UtilDateTime.nowTimestamp()
    delegator.create('CaseType', [
            caseTypeId             : caseTypeId,
            caseTypeName           : parameters.caseTypeName ?: caseTypeId,
            description            : parameters.description,
            primaryEntityName      : parameters.primaryEntityName,
            extensionEntityName    : parameters.extensionEntityName,
            extensionFormId        : parameters.extensionFormId,
            definitionVersion      : 1,
            statusId               : 'CASE_TYPE_DRAFT',
            defaultSlaId           : parameters.defaultSlaId,
            accessPolicyId         : parameters.accessPolicyId,
            createdDate            : now,
            lastModifiedDate       : now
    ])
    return successMap([caseTypeId: caseTypeId, definitionVersion: 1])
}

Map updateCaseTypeDraft() {
    GenericValue caseType = lockCaseType(parameters.caseTypeId)
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    if (!CaseTypeDraftHelper.hasEditableDraft(caseType)) {
        return errorMap("Case type is not a draft: ${parameters.caseTypeId}")
    }
    if (CaseTypeDraftHelper.isPublished(caseType)) {
        // CaseType header fields are not versioned and are read by running cases.
        return errorMap("The case type is published; its name, SLA and other header fields cannot be changed in a draft: ${parameters.caseTypeId}")
    }
    ['caseTypeName', 'description', 'primaryEntityName', 'extensionEntityName',
     'extensionFormId', 'defaultSlaId', 'accessPolicyId'].each { String field ->
        if (parameters.containsKey(field)) {
            caseType.set(field, parameters.get(field))
        }
    }
    caseType.lastModifiedDate = UtilDateTime.nowTimestamp()
    caseType.store()
    return successMap([caseTypeId: caseType.caseTypeId])
}

Map cloneCaseTypeDefinition() {
    GenericValue caseType = lockCaseType(parameters.caseTypeId)
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    if (caseType.statusId != CaseTypeDraftHelper.ACTIVE || !CaseTypeDraftHelper.isPublished(caseType)) {
        return errorMap("Only a published case type can be cloned: ${parameters.caseTypeId}")
    }
    if (CaseTypeDraftHelper.hasEditableDraft(caseType)) {
        return errorMap("A draft already exists: ${caseType.caseTypeId} version ${caseType.definitionVersion}")
    }
    long activeVersion = caseType.activeDefinitionVersion as long
    long newVersion = activeVersion + 1
    // A failed or interrupted run can leave rows for the target version.
    deleteDefinitionRows(caseType.caseTypeId, newVersion)
    cloneDefinition(caseType.caseTypeId, activeVersion, newVersion)
    cloneVersionAccessPolicy(caseType, activeVersion, newVersion)
    // statusId stays ACTIVE: the published version keeps serving new cases and sub-cases.
    caseType.definitionVersion = newVersion
    caseType.lastModifiedDate = UtilDateTime.nowTimestamp()
    caseType.store()
    return successMap([caseTypeId: caseType.caseTypeId, definitionVersion: newVersion])
}

Map validateCaseTypeDefinition() {
    GenericValue caseType = from('CaseType').where('caseTypeId', parameters.caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    long version = (parameters.definitionVersion ?: caseType.definitionVersion ?: 1) as long
    List<String> errors = validateDefinition(caseType, version)
    return successMap([valid: errors.isEmpty(), errors: errors, definitionVersion: version])
}

Map publishCaseTypeDefinition() {
    GenericValue caseType = lockCaseType(parameters.caseTypeId)
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    if (!CaseTypeDraftHelper.hasEditableDraft(caseType)) {
        return errorMap("There is no draft to publish: ${parameters.caseTypeId}")
    }
    long version = CaseTypeDraftHelper.draftVersion(caseType)
    if (parameters.definitionVersion != null && (parameters.definitionVersion as long) != version) {
        return errorMap("Definition version mismatch: expected $version, got ${parameters.definitionVersion}")
    }
    if (parameters.expectedRevision) {
        String currentRevision = computeCaseTypeDefinitionRevision(caseType.caseTypeId, version)
        if (parameters.expectedRevision != currentRevision) {
            Map conflict = errorMap('Case type definition was changed by another user')
            conflict.errorType = 'CONFLICT'
            conflict.currentRevision = currentRevision
            return conflict
        }
    }
    List<String> errors = validateDefinition(caseType, version)
    if (!errors.isEmpty()) {
        return errorMap("Case type definition is invalid: ${errors.join('; ')}")
    }
    caseType.activeDefinitionVersion = version
    GenericValue publishedPolicy = findVersionAccessPolicy(caseType.caseTypeId, version) ?: findAccessPolicy(caseType)
    if (publishedPolicy != null) {
        caseType.accessPolicyId = publishedPolicy.accessPolicyId
    }
    caseType.statusId = CaseTypeDraftHelper.ACTIVE
    caseType.lastModifiedDate = UtilDateTime.nowTimestamp()
    caseType.store()
    return successMap([caseTypeId: caseType.caseTypeId, definitionVersion: version])
}

Map retireCaseType() {
    GenericValue caseType = lockCaseType(parameters.caseTypeId)
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    if (caseType.statusId != CaseTypeDraftHelper.ACTIVE) {
        return errorMap("Only a published case type can be retired: ${parameters.caseTypeId}")
    }
    if (CaseTypeDraftHelper.hasEditableDraft(caseType)) {
        return errorMap("Discard or publish the draft before retiring: ${parameters.caseTypeId}")
    }
    caseType.statusId = CaseTypeDraftHelper.RETIRED
    caseType.lastModifiedDate = UtilDateTime.nowTimestamp()
    caseType.store()
    return successMap([caseTypeId: caseType.caseTypeId])
}

/**
 * Reads the CaseType row after taking its row lock, so concurrent save, publish, clone and discard
 * run one at a time. The entity engine has no SELECT ... FOR UPDATE, so the lock comes from an
 * UPDATE of the audit stamp (not part of the revision, and no business value is written back);
 * it is held until the service transaction ends, and the row is read again afterwards.
 */
private GenericValue lockCaseType(Object caseTypeId) {
    if (!caseTypeId) {
        return null
    }
    int updated = delegator.storeByCondition('CaseType', [lastUpdatedStamp: UtilDateTime.nowTimestamp()],
            EntityCondition.makeCondition('caseTypeId', caseTypeId))
    if (updated == 0) {
        return null
    }
    return from('CaseType').where('caseTypeId', caseTypeId).queryOne()
}

Map createCaseStageDef() {
    Map draftCheck = checkDraft(parameters.caseTypeId, parameters.definitionVersion)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = (parameters.stageSeq ?: 0) as long
    if (!stageSeq) {
        List<GenericValue> existingStages = getStages(parameters.caseTypeId, version)
        stageSeq = existingStages.isEmpty() ? 1L :
                ((existingStages.collect { GenericValue stage -> stage.stageSeq as long }.max() ?: 0L) + 1L)
    }
    if (from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
    ])).queryOne() != null) {
        return errorMap("Stage already exists: ${parameters.caseTypeId} / $version / $stageSeq")
    }
    delegator.create('CaseStageDef', [
            caseTypeId       : parameters.caseTypeId,
            definitionVersion: version,
            stageSeq         : stageSeq,
            stageName        : parameters.stageName ?: "Stage $stageSeq",
            stageTypeEnumId  : parameters.stageTypeEnumId ?: 'CASE_STAGE_PRIMARY',
            autoAdvance      : parameters.autoAdvance ?: 'N',
            entryServiceName : parameters.entryServiceName,
            exitServiceName  : parameters.exitServiceName,
            description      : parameters.description
    ])
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq])
}

Map updateCaseStageDef() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    GenericValue stage = from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
    ])).queryOne()
    if (stage == null) {
        return errorMap("Stage not found: ${parameters.caseTypeId} / $version / $stageSeq")
    }
    ['stageName', 'stageTypeEnumId', 'autoAdvance', 'entryServiceName', 'exitServiceName', 'description'].each { String field ->
        if (parameters.containsKey(field)) {
            stage.set(field, parameters.get(field))
        }
    }
    stage.store()
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq])
}

Map createCaseStepDef() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = (parameters.stageSeq ?: 0) as long
    long stepSeq = (parameters.stepSeq ?: 0) as long
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    if (!stageSeq) {
        List<GenericValue> stages = getStages(parameters.caseTypeId, version)
        if (stages.isEmpty()) {
            return errorMap('A stage is required before adding a step')
        }
        stageSeq = stages.last().stageSeq as long
    }
    if (!stepSeq) {
        List<GenericValue> existingSteps = from('CaseStepDef').where(EntityCondition.makeCondition([
                caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
        ])).queryList()
        stepSeq = existingSteps.isEmpty() ? 1L :
                ((existingSteps.collect { GenericValue step -> step.stepSeq as long }.max() ?: 0L) + 1L)
    }
    if (from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: stepSeq
    ])).queryOne() != null) {
        return errorMap("Step already exists: ${parameters.caseTypeId} / $version / $stageSeq / $stepSeq")
    }
    if (parameters.subCaseTypeId &&
            from('CaseType').where('caseTypeId', parameters.subCaseTypeId).queryOne() == null) {
        return errorMap("Sub-case type not found: ${parameters.subCaseTypeId}")
    }
    String stepTypeEnumId = parameters.stepTypeEnumId ?: 'CASE_STEP_HUMAN_TASK'
    String signalName = parameters.signalName?.trim() ?: null
    String timeoutOutcomeEnumId = parameters.timeoutOutcomeEnumId?.trim() ?: null
    long timeoutDuration = (parameters.timeoutDuration ?: 0) as long
    if (timeoutDuration < 0) {
        return errorMap('timeoutDuration must be zero or greater')
    }
    if (timeoutOutcomeEnumId && from('Enumeration').where(EntityCondition.makeCondition([
            enumTypeId: 'CASE_OUTCOME', enumId: timeoutOutcomeEnumId
    ])).queryOne() == null) {
        return errorMap("Invalid timeout outcome: $timeoutOutcomeEnumId")
    }
    if (timeoutDuration > 0 && !timeoutOutcomeEnumId) {
        return errorMap('timeoutOutcomeEnumId is required when timeoutDuration is greater than zero')
    }
    delegator.create('CaseStepDef', [
            caseTypeId         : parameters.caseTypeId,
            definitionVersion  : version,
            stageSeq           : stageSeq,
            stepSeq            : stepSeq,
            stepName           : parameters.stepName ?: "Step $stepSeq",
            stepTypeEnumId     : stepTypeEnumId,
            serviceName        : parameters.serviceName,
            formId             : parameters.formId,
            subCaseTypeId      : parameters.subCaseTypeId,
            assignmentPolicyId : parameters.assignmentPolicyId,
            slaId              : parameters.slaId,
            requiredIndicator  : parameters.requiredIndicator ?: 'Y',
            sequenceNum        : parameters.sequenceNum ?: stepSeq,
            description        : parameters.description
    ])
    if (stepTypeEnumId == 'CASE_STEP_WAIT' &&
            (signalName || timeoutDuration > 0 || timeoutOutcomeEnumId)) {
        delegator.create('CaseStepWait', [
                caseTypeId          : parameters.caseTypeId,
                definitionVersion   : version,
                stageSeq            : stageSeq,
                stepSeq             : stepSeq,
                signalName          : signalName,
                timeoutDuration     : timeoutDuration > 0 ? timeoutDuration : null,
                timeoutOutcomeEnumId: timeoutOutcomeEnumId,
                description         : parameters.description
        ])
    }
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version,
                       stageSeq: stageSeq, stepSeq: stepSeq])
}

Map updateCaseStepDef() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    long stepSeq = parameters.stepSeq as long
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    GenericValue step = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: stepSeq
    ])).queryOne()
    if (step == null) {
        return errorMap("Step not found: ${parameters.caseTypeId} / $version / $stageSeq / $stepSeq")
    }
    if (parameters.subCaseTypeId &&
            from('CaseType').where('caseTypeId', parameters.subCaseTypeId).queryOne() == null) {
        return errorMap("Sub-case type not found: ${parameters.subCaseTypeId}")
    }
    ['stepName', 'stepTypeEnumId', 'serviceName', 'formId', 'subCaseTypeId',
     'assignmentPolicyId', 'slaId', 'requiredIndicator', 'sequenceNum', 'description'].each { String field ->
        if (parameters.containsKey(field)) {
            step.set(field, parameters.get(field))
        }
    }
    step.store()
    if (parameters.containsKey('stepTypeEnumId') && parameters.stepTypeEnumId != 'CASE_STEP_WAIT') {
        from('CaseStepWait').where(EntityCondition.makeCondition([
                caseTypeId       : parameters.caseTypeId,
                definitionVersion: version,
                stageSeq         : stageSeq,
                stepSeq          : stepSeq
        ])).queryList().each { GenericValue wait -> delegator.removeValue(wait) }
    }
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version,
                       stageSeq: stageSeq, stepSeq: stepSeq])
}

Map deleteCaseStageDef() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    List<GenericValue> transitions = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version
    ])).queryList().findAll { GenericValue transition ->
        (transition.stageSeq as long) == stageSeq || (transition.toStageSeq != null && (transition.toStageSeq as long) == stageSeq)
    }
    transitions.each { GenericValue transition -> delegator.removeValue(transition) }
    from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
    ])).queryList().each { GenericValue mapping -> delegator.removeValue(mapping) }
    from('CaseFieldLabel').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
    ])).queryList().each { GenericValue label -> delegator.removeValue(label) }
    from('CaseStepForm').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
    ])).queryList().each { GenericValue mapping -> delegator.removeValue(mapping) }
    from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
    ])).queryList().each { GenericValue mapping -> delegator.removeValue(mapping) }
    from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
    ])).queryList().each { GenericValue mapping -> delegator.removeValue(mapping) }
    from('CaseStepWait').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
    ])).queryList().each { GenericValue wait -> delegator.removeValue(wait) }
    from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
    ])).queryList().each { GenericValue step -> delegator.removeValue(step) }
    GenericValue stage = from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
    ])).queryOne()
    if (stage == null) {
        return errorMap("Stage not found: ${parameters.caseTypeId} / $version / $stageSeq")
    }
    delegator.removeValue(stage)
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq])
}

Map moveCaseStageDef() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    List<GenericValue> stages = getStages(parameters.caseTypeId, version)
    int index = stages.findIndexOf { GenericValue stage -> (stage.stageSeq as long) == stageSeq }
    if (index < 0) {
        return errorMap("Stage not found: ${parameters.caseTypeId} / $version / $stageSeq")
    }
    int targetIndex = 'up' == parameters.direction ? index - 1 : index + 1
    if (targetIndex < 0 || targetIndex >= stages.size()) {
        return successMap([moved: false, caseTypeId: parameters.caseTypeId, definitionVersion: version])
    }
    long targetSeq = stages[targetIndex].stageSeq as long
    long tempSeq = ((stages.collect { GenericValue stage -> stage.stageSeq as long }.max() ?: 0L) + 1000L)
    changeStageSeq(parameters.caseTypeId, version, stageSeq, tempSeq)
    changeStageSeq(parameters.caseTypeId, version, targetSeq, stageSeq)
    changeStageSeq(parameters.caseTypeId, version, tempSeq, targetSeq)
    return successMap([moved: true, caseTypeId: parameters.caseTypeId, definitionVersion: version])
}

Map deleteCaseStepDef() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    long stepSeq = parameters.stepSeq as long
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    List<GenericValue> transitions = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version
    ])).queryList().findAll { GenericValue transition ->
        ((transition.stageSeq as long) == stageSeq && (transition.stepSeq as long) == stepSeq) ||
                (transition.toStageSeq != null && (transition.toStageSeq as long) == stageSeq &&
                        transition.toStepSeq != null && (transition.toStepSeq as long) == stepSeq)
    }
    transitions.each { GenericValue transition -> delegator.removeValue(transition) }
    from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: stepSeq
    ])).queryList().each { GenericValue mapping -> delegator.removeValue(mapping) }
    from('CaseFieldLabel').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: stepSeq
    ])).queryList().each { GenericValue label -> delegator.removeValue(label) }
    from('CaseStepForm').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: stepSeq
    ])).queryList().each { GenericValue mapping -> delegator.removeValue(mapping) }
    from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: stepSeq
    ])).queryList().each { GenericValue mapping -> delegator.removeValue(mapping) }
    from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: stepSeq
    ])).queryList().each { GenericValue mapping -> delegator.removeValue(mapping) }
    from('CaseStepWait').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: stepSeq
    ])).queryList().each { GenericValue wait -> delegator.removeValue(wait) }
    GenericValue step = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: stepSeq
    ])).queryOne()
    if (step == null) {
        return errorMap("Step not found: ${parameters.caseTypeId} / $version / $stageSeq / $stepSeq")
    }
    delegator.removeValue(step)
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version,
                       stageSeq: stageSeq, stepSeq: stepSeq])
}

Map moveCaseStepDef() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    long stepSeq = parameters.stepSeq as long
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    List<GenericValue> steps = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version, stageSeq: stageSeq
    ])).orderBy('stepSeq').queryList()
    int index = steps.findIndexOf { GenericValue step -> (step.stepSeq as long) == stepSeq }
    if (index < 0) {
        return errorMap("Step not found: ${parameters.caseTypeId} / $version / $stageSeq / $stepSeq")
    }
    int targetIndex = 'up' == parameters.direction ? index - 1 : index + 1
    if (targetIndex < 0 || targetIndex >= steps.size()) {
        return successMap([moved: false, caseTypeId: parameters.caseTypeId, definitionVersion: version])
    }
    long targetSeq = steps[targetIndex].stepSeq as long
    long tempSeq = ((steps.collect { GenericValue step -> step.stepSeq as long }.max() ?: 0L) + 1000L)
    changeStepSeq(parameters.caseTypeId, version, stageSeq, stepSeq, tempSeq)
    changeStepSeq(parameters.caseTypeId, version, stageSeq, targetSeq, stepSeq)
    changeStepSeq(parameters.caseTypeId, version, stageSeq, tempSeq, targetSeq)
    return successMap([moved: true, caseTypeId: parameters.caseTypeId, definitionVersion: version])
}

Map deleteCaseStepTransition() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    long stepSeq = parameters.stepSeq as long
    long transitionSeq = parameters.transitionSeq as long
    String outcomeEnumId = parameters.outcomeEnumId ?: 'CASE_OUT_COMPLETE'
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    GenericValue transition = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId,
            definitionVersion: version,
            stageSeq: stageSeq,
            stepSeq: stepSeq,
            outcomeEnumId: outcomeEnumId,
            transitionSeq: transitionSeq
    ])).queryOne()
    if (transition == null) {
        return errorMap('Transition not found')
    }
    delegator.removeValue(transition)
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version,
                       stageSeq: stageSeq, stepSeq: stepSeq, transitionSeq: transitionSeq])
}

Map moveCaseStepTransition() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    long stepSeq = parameters.stepSeq as long
    long transitionSeq = parameters.transitionSeq as long
    String outcomeEnumId = parameters.outcomeEnumId ?: 'CASE_OUT_COMPLETE'
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    List<GenericValue> transitions = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId,
            definitionVersion: version,
            stageSeq: stageSeq,
            stepSeq: stepSeq,
            outcomeEnumId: outcomeEnumId
    ])).orderBy('transitionSeq').queryList()
    int index = transitions.findIndexOf { GenericValue transition -> (transition.transitionSeq as long) == transitionSeq }
    if (index < 0) {
        return errorMap('Transition not found')
    }
    int targetIndex = 'up' == parameters.direction ? index - 1 : index + 1
    if (targetIndex < 0 || targetIndex >= transitions.size()) {
        return successMap([moved: false, caseTypeId: parameters.caseTypeId, definitionVersion: version])
    }
    long targetSeq = transitions[targetIndex].transitionSeq as long
    long tempSeq = ((transitions.collect { GenericValue transition -> transition.transitionSeq as long }.max() ?: 0L) + 1000L)
    changeTransitionSeq(parameters.caseTypeId, version, stageSeq, stepSeq, outcomeEnumId, transitionSeq, tempSeq)
    changeTransitionSeq(parameters.caseTypeId, version, stageSeq, stepSeq, outcomeEnumId, targetSeq, transitionSeq)
    changeTransitionSeq(parameters.caseTypeId, version, stageSeq, stepSeq, outcomeEnumId, tempSeq, targetSeq)
    return successMap([moved: true, caseTypeId: parameters.caseTypeId, definitionVersion: version])
}

Map createCaseStepTransition() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    long stepSeq = parameters.stepSeq as long
    String outcomeEnumId = parameters.outcomeEnumId ?: 'CASE_OUT_COMPLETE'
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    List<GenericValue> existing = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId,
            definitionVersion: version,
            stageSeq: stageSeq,
            stepSeq: stepSeq,
            outcomeEnumId: outcomeEnumId
    ])).queryList()
    long transitionSeq = (parameters.transitionSeq ?: 0) as long
    if (!transitionSeq) {
        transitionSeq = 1L
        existing.each { GenericValue transition ->
            long value = transition.transitionSeq as long
            if (value >= transitionSeq) {
                transitionSeq = value + 1
            }
        }
    }
    delegator.create('CaseStepTransition', [
            caseTypeId          : parameters.caseTypeId,
            definitionVersion   : version,
            stageSeq            : stageSeq,
            stepSeq             : stepSeq,
            outcomeEnumId       : outcomeEnumId,
            transitionSeq       : transitionSeq,
            toStageSeq          : parameters.toStageSeq,
            toStepSeq           : parameters.toStepSeq,
            conditionServiceName: parameters.conditionServiceName,
            priority            : parameters.priority ?: 0,
            isDefault           : parameters.isDefault ?: 'N',
            description         : parameters.description
    ])
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version,
                       stageSeq: stageSeq, stepSeq: stepSeq, transitionSeq: transitionSeq])
}

Map updateCaseStepTransition() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    long stepSeq = parameters.stepSeq as long
    long transitionSeq = parameters.transitionSeq as long
    String outcomeEnumId = parameters.outcomeEnumId ?: 'CASE_OUT_COMPLETE'
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    GenericValue transition = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId,
            definitionVersion: version,
            stageSeq: stageSeq,
            stepSeq: stepSeq,
            outcomeEnumId: outcomeEnumId,
            transitionSeq: transitionSeq
    ])).queryOne()
    if (transition == null) {
        return errorMap("Transition not found")
    }
    ['toStageSeq', 'toStepSeq', 'conditionServiceName', 'priority', 'isDefault', 'description'].each { String field ->
        if (parameters.containsKey(field)) {
            transition.set(field, parameters.get(field))
        }
    }
    transition.store()
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version,
                       stageSeq: stageSeq, stepSeq: stepSeq, transitionSeq: transitionSeq])
}

Map getCaseTypeDefinitionDiff() {
    GenericValue caseType = from('CaseType').where('caseTypeId', parameters.caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    long fromVersion = (parameters.fromVersion ?: caseType.activeDefinitionVersion ?: 1) as long
    long toVersion = (parameters.toVersion ?: caseType.definitionVersion ?: 1) as long
    Map<String, Long> stageMoves = fromVersion < toVersion
            ? computeStageMoves(caseType.caseTypeId, fromVersion, toVersion) : [:]
    Map<String, String> stepMoves = fromVersion < toVersion
            ? computeStepMoves(caseType.caseTypeId, fromVersion, toVersion) : [:]
    List<Map<String, Object>> stageChanges = diffStages(caseType.caseTypeId, fromVersion, toVersion, stageMoves)
    List<Map<String, Object>> stepChanges = diffSteps(caseType.caseTypeId, fromVersion, toVersion, stepMoves)
    List<Map<String, Object>> transitionChanges = diffTransitions(caseType.caseTypeId, fromVersion, toVersion,
            stageMoves, stepMoves)
    List<Map<String, Object>> fieldChanges = diffFields(caseType.caseTypeId, fromVersion, toVersion,
            stageMoves, stepMoves)
    List<Map<String, Object>> serviceParamChanges = diffServiceParams(caseType.caseTypeId, fromVersion, toVersion,
            stageMoves, stepMoves)
    List<Map<String, Object>> serviceOutputChanges = diffServiceOutputs(caseType.caseTypeId, fromVersion, toVersion,
            stageMoves, stepMoves)
    List<Map<String, Object>> startFormChanges = diffStartForm(caseType.caseTypeId, fromVersion, toVersion)
    List<Map<String, Object>> stepFormChanges = diffStepForms(caseType.caseTypeId, fromVersion, toVersion,
            stageMoves, stepMoves)
    List<Map<String, Object>> waitChanges = diffWaits(caseType.caseTypeId, fromVersion, toVersion,
            stageMoves, stepMoves)
    List<Map<String, Object>> accessPolicyChanges = diffAccessPolicy(caseType.caseTypeId, fromVersion, toVersion)
    List<Map<String, Object>> accessRuleChanges = diffAccessRules(caseType.caseTypeId, fromVersion, toVersion)
    int total = stageChanges.size() + stepChanges.size() + transitionChanges.size() +
            fieldChanges.size() + serviceParamChanges.size() + serviceOutputChanges.size() +
            startFormChanges.size() + stepFormChanges.size() + waitChanges.size() +
            accessPolicyChanges.size() + accessRuleChanges.size()
    return successMap([
            caseTypeId         : caseType.caseTypeId,
            fromVersion        : fromVersion,
            toVersion          : toVersion,
            hasChanges         : total > 0,
            changeCount        : total,
            stageChanges       : stageChanges,
            stepChanges        : stepChanges,
            transitionChanges  : transitionChanges,
            fieldChanges       : fieldChanges,
            serviceParamChanges: serviceParamChanges,
            serviceOutputChanges: serviceOutputChanges,
            startFormChanges   : startFormChanges,
            stepFormChanges    : stepFormChanges,
            waitChanges        : waitChanges,
            accessPolicyChanges: accessPolicyChanges,
            accessRuleChanges  : accessRuleChanges
    ])
}

private Map<String, Long> computeStageMoves(String caseTypeId, long fromVersion, long toVersion) {
    List<GenericValue> before = from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).orderBy('stageSeq').queryList()
    List<GenericValue> after = from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).orderBy('stageSeq').queryList()
    Map<String, List<GenericValue>> beforeByName = before.findAll { GenericValue value -> value.stageName }
            .groupBy { GenericValue value -> value.stageName as String }
    Map<String, List<GenericValue>> afterByName = after.findAll { GenericValue value -> value.stageName }
            .groupBy { GenericValue value -> value.stageName as String }
    Map<String, Long> moves = [:]
    beforeByName.each { String name, List<GenericValue> rows ->
        List<GenericValue> afterRows = afterByName[name]
        if (rows.size() != 1 || afterRows == null || afterRows.size() != 1) {
            return
        }
        long oldSeq = rows[0].stageSeq as long
        long newSeq = afterRows[0].stageSeq as long
        if (oldSeq != newSeq) {
            moves[oldSeq.toString()] = newSeq
        }
    }
    return moves
}

private Map<String, String> computeStepMoves(String caseTypeId, long fromVersion, long toVersion) {
    Map<String, String> beforeStageNames = stageNamesBySeq(caseTypeId, fromVersion)
    Map<String, String> afterStageNames = stageNamesBySeq(caseTypeId, toVersion)
    List<GenericValue> before = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).orderBy('stageSeq', 'stepSeq').queryList()
    List<GenericValue> after = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).orderBy('stageSeq', 'stepSeq').queryList()
    Map<String, List<GenericValue>> beforeByKey = before.findAll { GenericValue value -> value.stepName }
            .groupBy { GenericValue value ->
                ("${beforeStageNames[value.stageSeq.toString()] ?: value.stageSeq}:${value.stepName}" as String)
            }
    Map<String, List<GenericValue>> afterByKey = after.findAll { GenericValue value -> value.stepName }
            .groupBy { GenericValue value ->
                ("${afterStageNames[value.stageSeq.toString()] ?: value.stageSeq}:${value.stepName}" as String)
            }
    Map<String, String> moves = [:]
    beforeByKey.each { String key, List<GenericValue> rows ->
        List<GenericValue> afterRows = afterByKey[key]
        if (rows.size() != 1 || afterRows == null || afterRows.size() != 1) {
            return
        }
        GenericValue oldStep = rows[0]
        GenericValue newStep = afterRows[0]
        long oldStage = oldStep.stageSeq as long
        long oldSeq = oldStep.stepSeq as long
        long newStage = newStep.stageSeq as long
        long newSeq = newStep.stepSeq as long
        if (oldStage != newStage || oldSeq != newSeq) {
            moves["${oldStage}/${oldSeq}".toString()] = "${newStage}/${newSeq}".toString()
        }
    }
    return moves
}

private Map<String, String> stageNamesBySeq(String caseTypeId, long version) {
    return from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).queryList().collectEntries { GenericValue value ->
        [(value.stageSeq.toString()): value.stageName]
    }
}

private String remapChildKey(String key, Map<String, Long> stageMoves, Map<String, String> stepMoves) {
    List<String> parts = key.split('/') as List
    if (parts.size() < 2) {
        return key
    }
    String movedStep = stepMoves["${parts[0]}/${parts[1]}".toString()]
    if (movedStep != null) {
        List<String> movedParts = movedStep.split('/') as List
        parts[0] = movedParts[0]
        parts[1] = movedParts[1]
    } else {
        Long movedStage = stageMoves[parts[0]]
        if (movedStage != null) {
            parts[0] = movedStage.toString()
        }
    }
    return parts.join('/')
}

private Map<String, GenericValue> remapBeforeKeys(Map<String, GenericValue> before,
                                                   Map<String, Long> stageMoves,
                                                   Map<String, String> stepMoves) {
    Map<String, GenericValue> remapped = [:]
    before.each { String key, GenericValue value ->
        remapped[remapChildKey(key, stageMoves, stepMoves)] = value
    }
    return remapped
}

private Map<String, String> computeMovedKeys(Map<String, GenericValue> before, Map<String, GenericValue> after,
                                             Closure signature) {
    Map<String, List<String>> beforeBySignature = [:]
    before.each { String key, GenericValue value ->
        String signatureValue = signature.call(value) as String
        List<String> keys = beforeBySignature[signatureValue]
        if (keys == null) {
            keys = []
            beforeBySignature[signatureValue] = keys
        }
        keys << key
    }
    Map<String, List<String>> afterBySignature = [:]
    after.each { String key, GenericValue value ->
        String signatureValue = signature.call(value) as String
        List<String> keys = afterBySignature[signatureValue]
        if (keys == null) {
            keys = []
            afterBySignature[signatureValue] = keys
        }
        keys << key
    }
    Map<String, String> moves = [:]
    beforeBySignature.each { String signatureValue, List<String> beforeKeys ->
        List<String> afterKeys = afterBySignature[signatureValue]
        if (beforeKeys.size() == 1 && afterKeys != null && afterKeys.size() == 1 &&
                beforeKeys[0] != afterKeys[0]) {
            moves[beforeKeys[0]] = afterKeys[0]
        }
    }
    return moves
}

private String transitionMoveSignature(GenericValue value) {
    return [
            value.outcomeEnumId ?: '',
            value.toStageSeq ?: '',
            value.toStepSeq ?: '',
            value.conditionServiceName ?: '',
            value.priority != null ? value.priority : 0,
            value.isDefault ?: 'N'
    ].join('|')
}

private String stepFormMoveSignature(GenericValue value) {
    return [
            value.formLocation ?: '',
            value.formName ?: '',
            value.formTarget ?: '',
            value.completeServiceName ?: ''
    ].join('|')
}

private String describeTransition(GenericValue value) {
    return "to=${value.toStageSeq ?: ''}/${value.toStepSeq ?: ''} / priority=${value.priority ?: 0} / default=${value.isDefault} / condition=${value.conditionServiceName ?: ''}"
}

private String describeStepForm(GenericValue value) {
    return "${value.formLocation}#${value.formName} / target=${value.formTarget} / service=${value.completeServiceName ?: ''}"
}

private GenericValue remapTransitionTargets(GenericValue transition, Map<String, Long> stageMoves,
                                            Map<String, String> stepMoves) {
    Long toStageSeq = transition.toStageSeq as Long
    if (toStageSeq == null) {
        return transition
    }
    Long toStepSeq = transition.toStepSeq as Long
    Long newToStageSeq = toStageSeq
    Long newToStepSeq = toStepSeq
    String movedStep = toStepSeq == null ? null : stepMoves["${toStageSeq}/${toStepSeq}".toString()]
    if (movedStep != null) {
        List<String> movedParts = movedStep.split('/') as List
        newToStageSeq = movedParts[0] as Long
        newToStepSeq = movedParts[1] as Long
    } else {
        Long movedStage = stageMoves[toStageSeq.toString()]
        if (movedStage != null) {
            newToStageSeq = movedStage
        }
    }
    if (newToStageSeq == toStageSeq && newToStepSeq == toStepSeq) {
        return transition
    }
    GenericValue copy = delegator.makeValue('CaseStepTransition', transition.getAllFields())
    copy.toStageSeq = newToStageSeq
    copy.toStepSeq = newToStepSeq
    return copy
}

private String describeStage(GenericValue value) {
    return "Stage ${value.stageSeq}: ${value.stageName} / autoAdvance=${value.autoAdvance} / entry=${value.entryServiceName ?: ''} / exit=${value.exitServiceName ?: ''}"
}

private String describeStep(GenericValue value) {
    return "${value.stepName} / ${value.stepTypeEnumId} / service=${value.serviceName ?: ''} / form=${value.formId ?: ''} / subCaseType=${value.subCaseTypeId ?: ''} / policy=${value.assignmentPolicyId ?: ''} / sla=${value.slaId ?: ''} / required=${value.requiredIndicator}"
}

private List<Map<String, Object>> diffStages(String caseTypeId, long fromVersion, long toVersion,
                                              Map<String, Long> stageMoves) {
    Map<String, GenericValue> before = from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value -> [(value.stageSeq.toString()): value] }
    Map<String, GenericValue> after = from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value -> [(value.stageSeq.toString()): value] }
    List<Map<String, Object>> moves = []
    stageMoves.each { String oldSeq, Long newSeq ->
        GenericValue oldStage = before[oldSeq]
        GenericValue newStage = after[newSeq.toString()]
        if (oldStage == null || newStage == null) {
            return
        }
        moves << [
                keyType        : 'stageSeq',
                key            : oldSeq,
                change         : 'MOVED',
                before         : describeStage(oldStage),
                after          : describeStage(newStage),
                targetStageSeq : newSeq
        ]
        before.remove(oldSeq)
        after.remove(newSeq.toString())
    }
    return moves + diffMaps(before, after, { GenericValue value -> describeStage(value) }, 'stageSeq')
}

private List<Map<String, Object>> diffSteps(String caseTypeId, long fromVersion, long toVersion,
                                            Map<String, String> stepMoves) {
    Map<String, GenericValue> before = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value -> [("${value.stageSeq}/${value.stepSeq}".toString()): value] }
    Map<String, GenericValue> after = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value -> [("${value.stageSeq}/${value.stepSeq}".toString()): value] }
    List<Map<String, Object>> moves = []
    stepMoves.each { String oldKey, String newKey ->
        GenericValue oldStep = before[oldKey]
        GenericValue newStep = after[newKey]
        if (oldStep == null || newStep == null) {
            return
        }
        moves << [
                keyType        : 'step',
                key            : oldKey,
                change         : 'MOVED',
                before         : describeStep(oldStep),
                after          : describeStep(newStep),
                targetStageSeq : newStep.stageSeq as long,
                targetStepSeq  : newStep.stepSeq as long
        ]
        before.remove(oldKey)
        after.remove(newKey)
    }
    return moves + diffMaps(before, after, { GenericValue value -> describeStep(value) }, 'step')
}

private List<Map<String, Object>> diffTransitions(String caseTypeId, long fromVersion, long toVersion,
                                                  Map<String, Long> stageMoves, Map<String, String> stepMoves) {
    Map<String, GenericValue> before = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        String key = remapChildKey(
                "${value.stageSeq}/${value.stepSeq}/${value.outcomeEnumId}/${value.transitionSeq}".toString(),
                stageMoves, stepMoves)
        [(key): remapTransitionTargets(value, stageMoves, stepMoves)]
    }
    Map<String, GenericValue> after = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.outcomeEnumId}/${value.transitionSeq}".toString()): value]
    }
    Map<String, String> moves = computeMovedKeys(before, after) { GenericValue value ->
        transitionMoveSignature(value)
    }
    List<Map<String, Object>> movedRows = []
    moves.each { String oldKey, String newKey ->
        GenericValue oldTransition = before[oldKey]
        GenericValue newTransition = after[newKey]
        if (oldTransition == null || newTransition == null) {
            return
        }
        List<String> parts = newKey.split('/') as List
        movedRows << [
                keyType        : 'transition',
                key            : oldKey,
                change         : 'MOVED',
                before         : describeTransition(oldTransition),
                after          : describeTransition(newTransition),
                targetStageSeq : parts[0] as Long,
                targetStepSeq  : parts[1] as Long
        ]
        before.remove(oldKey)
        after.remove(newKey)
    }
    return movedRows + diffMaps(before, after, { GenericValue value -> describeTransition(value) }, 'transition')
}

private List<Map<String, Object>> diffFields(String caseTypeId, long fromVersion, long toVersion,
                                             Map<String, Long> stageMoves, Map<String, String> stepMoves) {
    Map<String, GenericValue> before = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.fieldSeq}".toString()): value]
    }
    before = remapBeforeKeys(before, stageMoves, stepMoves)
    Map<String, GenericValue> after = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.fieldSeq}".toString()): value]
    }
    return diffMaps(before, after, { GenericValue value ->
        "${value.fieldName} -> ${value.entityName}.${value.entityFieldName} / ${value.label} / ${value.fieldTypeEnumId} / required=${value.requiredIndicator}"
    }, 'field')
}

private List<Map<String, Object>> diffServiceParams(String caseTypeId, long fromVersion, long toVersion,
                                                    Map<String, Long> stageMoves, Map<String, String> stepMoves) {
    Map<String, GenericValue> before = from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.paramSeq}".toString()): value]
    }
    before = remapBeforeKeys(before, stageMoves, stepMoves)
    Map<String, GenericValue> after = from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.paramSeq}".toString()): value]
    }
    return diffMaps(before, after, { GenericValue value ->
        "${value.paramName} <- ${value.sourceTypeEnumId}:${value.sourceValue ?: ''} / required=${value.requiredIndicator}"
    }, 'serviceInput')
}

private List<Map<String, Object>> diffServiceOutputs(String caseTypeId, long fromVersion, long toVersion,
                                                     Map<String, Long> stageMoves, Map<String, String> stepMoves) {
    Map<String, GenericValue> before = from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.outputSeq}".toString()): value]
    }
    before = remapBeforeKeys(before, stageMoves, stepMoves)
    Map<String, GenericValue> after = from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.outputSeq}".toString()): value]
    }
    return diffMaps(before, after, { GenericValue value ->
        "${value.outputName} -> ${value.variableName} / ${value.valueTypeEnumId} / scope=${value.scopeEnumId} / ttl=${value.ttlSeconds ?: ''} / sensitive=${value.sensitiveIndicator}"
    }, 'serviceOutput')
}

private List<Map<String, Object>> diffStartForm(String caseTypeId, long fromVersion, long toVersion) {
    GenericValue before = from('CaseTypeStartForm').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryOne()
    GenericValue after = from('CaseTypeStartForm').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryOne()
    Map<String, GenericValue> beforeMap = before == null ? [:] : [startForm: before]
    Map<String, GenericValue> afterMap = after == null ? [:] : [startForm: after]
    return diffMaps(beforeMap, afterMap, { GenericValue value ->
        "${value.formLocation}#${value.formName} / target=${value.formTarget} / service=${value.startServiceName ?: ''}"
    }, 'startForm')
}

private List<Map<String, Object>> diffStepForms(String caseTypeId, long fromVersion, long toVersion,
                                                 Map<String, Long> stageMoves, Map<String, String> stepMoves) {
    Map<String, GenericValue> before = from('CaseStepForm').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}".toString()): value]
    }
    before = remapBeforeKeys(before, stageMoves, stepMoves)
    Map<String, GenericValue> after = from('CaseStepForm').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}".toString()): value]
    }
    Map<String, String> moves = computeMovedKeys(before, after) { GenericValue value ->
        stepFormMoveSignature(value)
    }
    List<Map<String, Object>> movedRows = []
    moves.each { String oldKey, String newKey ->
        GenericValue oldForm = before[oldKey]
        GenericValue newForm = after[newKey]
        if (oldForm == null || newForm == null) {
            return
        }
        List<String> parts = newKey.split('/') as List
        movedRows << [
                keyType        : 'stepForm',
                key            : oldKey,
                change         : 'MOVED',
                before         : describeStepForm(oldForm),
                after          : describeStepForm(newForm),
                targetStageSeq : parts[0] as Long,
                targetStepSeq  : parts[1] as Long
        ]
        before.remove(oldKey)
        after.remove(newKey)
    }
    return movedRows + diffMaps(before, after, { GenericValue value -> describeStepForm(value) }, 'stepForm')
}

private List<Map<String, Object>> diffWaits(String caseTypeId, long fromVersion, long toVersion,
                                            Map<String, Long> stageMoves, Map<String, String> stepMoves) {
    Map<String, GenericValue> before = from('CaseStepWait').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}".toString()): value]
    }
    before = remapBeforeKeys(before, stageMoves, stepMoves)
    Map<String, GenericValue> after = from('CaseStepWait').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}".toString()): value]
    }
    return diffMaps(before, after, { GenericValue value ->
        "signal=${value.signalName ?: ''} / timeout=${value.timeoutDuration ?: ''} min / outcome=${value.timeoutOutcomeEnumId ?: ''} / ${value.description ?: ''}"
    }, 'waitSetting')
}

private List<Map<String, Object>> diffAccessPolicy(String caseTypeId, long fromVersion, long toVersion) {
    GenericValue caseType = from('CaseType').where('caseTypeId', caseTypeId).queryOne()
    GenericValue beforePolicy = findVersionAccessPolicy(caseTypeId, fromVersion) ?: findAccessPolicy(caseType)
    GenericValue afterPolicy = findVersionAccessPolicy(caseTypeId, toVersion) ?: findAccessPolicy(caseType)
    Map<String, GenericValue> before = beforePolicy == null ? [:] : [accessPolicy: beforePolicy]
    Map<String, GenericValue> after = afterPolicy == null ? [:] : [accessPolicy: afterPolicy]
    return diffMaps(before, after, { GenericValue value ->
        "description=${value.description ?: ''}"
    }, 'accessPolicy')
}

private List<Map<String, Object>> diffAccessRules(String caseTypeId, long fromVersion, long toVersion) {
    GenericValue caseType = from('CaseType').where('caseTypeId', caseTypeId).queryOne()
    GenericValue beforePolicy = findVersionAccessPolicy(caseTypeId, fromVersion) ?: findAccessPolicy(caseType)
    GenericValue afterPolicy = findVersionAccessPolicy(caseTypeId, toVersion) ?: findAccessPolicy(caseType)
    Map<String, GenericValue> before = beforePolicy == null ? [:] : from('CaseAccessRule')
            .where('accessPolicyId', beforePolicy.accessPolicyId)
            .orderBy('ruleSeq')
            .queryList()
            .collectEntries { GenericValue value -> [(value.ruleSeq.toString()): value] }
    Map<String, GenericValue> after = afterPolicy == null ? [:] : from('CaseAccessRule')
            .where('accessPolicyId', afterPolicy.accessPolicyId)
            .orderBy('ruleSeq')
            .queryList()
            .collectEntries { GenericValue value -> [(value.ruleSeq.toString()): value] }
    Map<String, String> beforeStageNames = stageNamesBySeq(caseTypeId, fromVersion)
    Map<String, String> afterStageNames = stageNamesBySeq(caseTypeId, toVersion)
    Map<String, String> beforeStepNames = stepNamesBySeq(caseTypeId, fromVersion)
    Map<String, String> afterStepNames = stepNamesBySeq(caseTypeId, toVersion)
    List<GenericValue> rules = []
    rules.addAll(before.values())
    rules.addAll(after.values())
    Map<String, String> conditionDescriptions = conditionServiceDescriptions(rules)
    Set<String> keys = new TreeSet<>()
    keys.addAll(before.keySet())
    keys.addAll(after.keySet())
    List<Map<String, Object>> changes = []
    keys.each { String key ->
        GenericValue oldValue = before[key]
        GenericValue newValue = after[key]
        String oldText = oldValue == null ? '' : describeAccessRule(
                oldValue, beforeStageNames, beforeStepNames, conditionDescriptions)
        String newText = newValue == null ? '' : describeAccessRule(
                newValue, afterStageNames, afterStepNames, conditionDescriptions)
        if (!oldText.equals(newText)) {
            changes << [
                    keyType: 'accessRule',
                    key    : key,
                    change : oldValue == null ? 'ADDED' : (newValue == null ? 'REMOVED' : 'CHANGED'),
                    before : oldText,
                    after  : newText
            ]
        }
    }
    return changes
}

private Map<String, String> stepNamesBySeq(String caseTypeId, long version) {
    return from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}".toString()): value.stepName]
    }
}

private Map<String, String> conditionServiceDescriptions(Collection<GenericValue> rules) {
    Set<String> names = new TreeSet<>()
    rules.each { GenericValue rule ->
        if (rule.conditionServiceName) {
            names << rule.conditionServiceName as String
        }
    }
    Map<String, String> descriptions = [:]
    if (names.isEmpty()) {
        return descriptions
    }
    try {
        def dispatchContext = dispatcher.getDispatchContext()
        names.each { String serviceName ->
            try {
                String description = normalizeServiceDescription(
                        dispatchContext.getModelService(serviceName)?.description as String)
                if (description) {
                    descriptions[serviceName] = description
                }
            } catch (Exception ignored) {
                // Fall back to the service definition XML below.
            }
        }
    } catch (Exception ignored) {
        // The XML fallback below still works without the dispatch context.
    }
    Set<String> missing = new TreeSet<>(names.findAll { String name -> !descriptions.containsKey(name) })
    if (missing.isEmpty()) {
        return descriptions
    }
    try {
        ComponentConfig.getAllServiceResourceInfos('model').each { ComponentConfig.ServiceResourceInfo info ->
            if (missing.isEmpty()) {
                return
            }
            try {
                Document document = info.createResourceHandler().document
                UtilXml.childElementList(document.documentElement, 'service').each { Element service ->
                    String serviceName = service.getAttribute('name')
                    if (serviceName && missing.contains(serviceName)) {
                        String description = normalizeServiceDescription(
                                UtilXml.childElementValue(service, 'description'))
                        if (description) {
                            descriptions[serviceName] = description
                            missing.remove(serviceName)
                        }
                    }
                }
            } catch (Exception ignored) {
                // A broken service resource must not block the diff.
            }
        }
    } catch (Exception ignored) {
        // The diff still works without service descriptions.
    }
    return descriptions
}

private String normalizeServiceDescription(String description) {
    if (!description) {
        return null
    }
    String normalized = description.replaceAll('\\s+', ' ').trim()
    return normalized ?: null
}

private String describeAccessRule(GenericValue rule, Map<String, String> stageNames,
                                  Map<String, String> stepNames,
                                  Map<String, String> conditionDescriptions) {
    List<String> parts = []
    if (rule.partyId) {
        parts << "party=${rule.partyId}"
    }
    if (rule.partyRoleTypeId) {
        parts << "role=${rule.partyRoleTypeId}"
    }
    if (rule.queueId) {
        parts << "queue=${rule.queueId}"
    }
    if (rule.permissionEnumId) {
        parts << "permission=${rule.permissionEnumId}"
    }
    if (rule.stageSeq != null) {
        String target = "target=${stageNames[rule.stageSeq.toString()] ?: rule.stageSeq}"
        if (rule.stepSeq != null) {
            target += "/${stepNames["${rule.stageSeq}/${rule.stepSeq}".toString()] ?: rule.stepSeq}"
        }
        parts << target
    }
    if (rule.conditionServiceName) {
        String condition = "condition=${rule.conditionServiceName}"
        String conditionDescription = conditionDescriptions[rule.conditionServiceName]
        if (conditionDescription) {
            condition += " (${conditionDescription})"
        }
        parts << condition
    }
    if (rule.description) {
        parts << rule.description
    }
    return parts.join(' / ')
}

private List<Map<String, Object>> diffMaps(Map<String, GenericValue> before,
                                           Map<String, GenericValue> after,
                                           Closure<String> describe,
                                           String keyType) {
    Set<String> keys = new TreeSet<>()
    keys.addAll(before.keySet())
    keys.addAll(after.keySet())
    List<Map<String, Object>> changes = []
    keys.each { String key ->
        GenericValue oldValue = before[key]
        GenericValue newValue = after[key]
        String oldText = oldValue == null ? '' : describe(oldValue)
        String newText = newValue == null ? '' : describe(newValue)
        if (!oldText.equals(newText)) {
            changes << [
                    keyType : keyType,
                    key     : key,
                    change  : oldValue == null ? 'ADDED' : (newValue == null ? 'REMOVED' : 'CHANGED'),
                    before  : oldText,
                    after   : newText
            ]
        }
    }
    return changes
}

Map createCaseFieldMapping() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    long stepSeq = parameters.stepSeq as long
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    List<GenericValue> existing = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version,
            stageSeq: stageSeq, stepSeq: stepSeq
    ])).queryList()
    long fieldSeq = (parameters.fieldSeq ?: 0) as long
    if (!fieldSeq) {
        fieldSeq = 1L
        existing.each { GenericValue mapping ->
            long value = mapping.fieldSeq as long
            if (value >= fieldSeq) {
                fieldSeq = value + 1
            }
        }
    }
    String fieldName = parameters.fieldName
    if (!fieldName) {
        return errorMap('fieldName is required')
    }
    if (from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version,
            stageSeq: stageSeq, stepSeq: stepSeq, fieldSeq: fieldSeq
    ])).queryOne() != null) {
        return errorMap("Field mapping already exists: $fieldSeq")
    }
    delegator.create('CaseFieldMapping', [
            caseTypeId         : parameters.caseTypeId,
            definitionVersion  : version,
            stageSeq           : stageSeq,
            stepSeq            : stepSeq,
            fieldSeq           : fieldSeq,
            fieldName          : fieldName,
            entityName         : parameters.entityName ?: 'Case',
            entityFieldName    : parameters.entityFieldName ?: fieldName,
            label              : parameters.label ?: fieldName,
            fieldTypeEnumId    : parameters.fieldTypeEnumId ?: 'CASE_FIELD_TEXT',
            requiredIndicator  : parameters.requiredIndicator ?: 'N',
            sequenceNum        : parameters.sequenceNum ?: fieldSeq,
            lookupEntityName   : parameters.lookupEntityName,
            lookupFieldName    : parameters.lookupFieldName,
            defaultValue       : parameters.defaultValue,
            visibilityCondition: parameters.visibilityCondition,
            readOnlyCondition  : parameters.readOnlyCondition,
            description        : parameters.description
    ])
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version,
                       stageSeq: stageSeq, stepSeq: stepSeq, fieldSeq: fieldSeq])
}

Map updateCaseFieldMapping() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    long stepSeq = parameters.stepSeq as long
    long fieldSeq = parameters.fieldSeq as long
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    GenericValue mapping = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version,
            stageSeq: stageSeq, stepSeq: stepSeq, fieldSeq: fieldSeq
    ])).queryOne()
    if (mapping == null) {
        return errorMap("Field mapping not found: $fieldSeq")
    }
    ['fieldName', 'entityName', 'entityFieldName', 'label', 'fieldTypeEnumId',
     'requiredIndicator', 'sequenceNum', 'lookupEntityName', 'lookupFieldName',
     'defaultValue', 'visibilityCondition', 'readOnlyCondition', 'description'].each { String field ->
        if (parameters.containsKey(field)) {
            mapping.set(field, parameters.get(field))
        }
    }
    mapping.store()
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version,
                       stageSeq: stageSeq, stepSeq: stepSeq, fieldSeq: fieldSeq])
}

Map deleteCaseFieldMapping() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    long stepSeq = parameters.stepSeq as long
    long fieldSeq = parameters.fieldSeq as long
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    GenericValue mapping = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version,
            stageSeq: stageSeq, stepSeq: stepSeq, fieldSeq: fieldSeq
    ])).queryOne()
    if (mapping == null) {
        return errorMap("Field mapping not found: $fieldSeq")
    }
    from('CaseFieldLabel').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version,
            stageSeq: stageSeq, stepSeq: stepSeq, fieldSeq: fieldSeq
    ])).queryList().each { GenericValue label -> delegator.removeValue(label) }
    delegator.removeValue(mapping)
    return successMap([caseTypeId: parameters.caseTypeId, definitionVersion: version,
                       stageSeq: stageSeq, stepSeq: stepSeq, fieldSeq: fieldSeq])
}

Map moveCaseFieldMapping() {
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = parameters.stageSeq as long
    long stepSeq = parameters.stepSeq as long
    long fieldSeq = parameters.fieldSeq as long
    Map draftCheck = checkDraft(parameters.caseTypeId, version)
    if (ServiceUtil.isError(draftCheck)) {
        return draftCheck
    }
    List<GenericValue> mappings = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: parameters.caseTypeId, definitionVersion: version,
            stageSeq: stageSeq, stepSeq: stepSeq
    ])).orderBy('fieldSeq').queryList()
    int index = mappings.findIndexOf { GenericValue mapping -> (mapping.fieldSeq as long) == fieldSeq }
    if (index < 0) {
        return errorMap("Field mapping not found: $fieldSeq")
    }
    int targetIndex = 'up' == parameters.direction ? index - 1 : index + 1
    if (targetIndex < 0 || targetIndex >= mappings.size()) {
        return successMap([moved: false])
    }
    long targetSeq = mappings[targetIndex].fieldSeq as long
    long tempSeq = ((mappings.collect { GenericValue mapping -> mapping.fieldSeq as long }.max() ?: 0L) + 1000L)
    changeFieldSeq(version, parameters.caseTypeId, stageSeq, stepSeq, fieldSeq, tempSeq)
    changeFieldSeq(version, parameters.caseTypeId, stageSeq, stepSeq, targetSeq, fieldSeq)
    changeFieldSeq(version, parameters.caseTypeId, stageSeq, stepSeq, tempSeq, targetSeq)
    return successMap([moved: true])
}

private void changeFieldSeq(long version, String caseTypeId, long stageSeq, long stepSeq, long oldSeq, long newSeq) {
    GenericValue mapping = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version,
            stageSeq: stageSeq, stepSeq: stepSeq, fieldSeq: oldSeq
    ])).queryOne()
    if (mapping != null) {
        List<GenericValue> labels = from('CaseFieldLabel').where(EntityCondition.makeCondition([
                caseTypeId: caseTypeId, definitionVersion: version,
                stageSeq: stageSeq, stepSeq: stepSeq, fieldSeq: oldSeq
        ])).queryList()
        Map<String, Object> fields = copyFields(mapping)
        fields.fieldSeq = newSeq
        fields.sequenceNum = newSeq
        labels.each { GenericValue label -> delegator.removeValue(label) }
        delegator.removeValue(mapping)
        delegator.create('CaseFieldMapping', fields)
        labels.each { GenericValue label ->
            Map<String, Object> labelFields = copyFields(label)
            labelFields.fieldSeq = newSeq
            delegator.create('CaseFieldLabel', labelFields)
        }
    }
}

Map deleteCaseTypeDraft() {
    GenericValue caseType = lockCaseType(parameters.caseTypeId)
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    if (caseType.statusId != CaseTypeDraftHelper.DRAFT || CaseTypeDraftHelper.isPublished(caseType)) {
        return errorMap("Only a case type that was never published can be deleted: ${parameters.caseTypeId}")
    }
    if (from('Case').where('caseTypeId', caseType.caseTypeId).queryOne() != null) {
        return errorMap("Case type has cases and cannot be deleted: ${parameters.caseTypeId}")
    }
    long version = (caseType.definitionVersion ?: 1) as long
    from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).queryList().each { GenericValue value -> delegator.removeValue(value) }
    from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).queryList().each { GenericValue value -> delegator.removeValue(value) }
    from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).queryList().each { GenericValue value -> delegator.removeValue(value) }
    from('CaseStepForm').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).queryList().each { GenericValue value -> delegator.removeValue(value) }
    from('CaseTypeStartForm').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).queryList().each { GenericValue value -> delegator.removeValue(value) }
    from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).queryList().each { GenericValue value -> delegator.removeValue(value) }
    from('CaseFieldLabel').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).queryList().each { GenericValue value -> delegator.removeValue(value) }
    from('CaseStepWait').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).queryList().each { GenericValue value -> delegator.removeValue(value) }
    from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).queryList().each { GenericValue value -> delegator.removeValue(value) }
    from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).queryList().each { GenericValue value -> delegator.removeValue(value) }
    from('CaseSlaRule').where('caseTypeId', caseType.caseTypeId).queryList()
            .each { GenericValue value -> delegator.removeValue(value) }
    from('CaseTypeAttr').where('caseTypeId', caseType.caseTypeId).queryList()
            .each { GenericValue value -> delegator.removeValue(value) }
    deleteAllAccessPolicies(caseType)
    delegator.removeValue(caseType)
    return successMap([caseTypeId: parameters.caseTypeId])
}

Map discardCaseTypeDraft() {
    String caseTypeId = parameters.caseTypeId
    GenericValue caseType = lockCaseType(caseTypeId)
    if (caseType == null) {
        return errorMap("Case type not found: $caseTypeId")
    }
    if (!CaseTypeDraftHelper.hasEditableDraft(caseType)) {
        return errorMap("Only a draft case type can be discarded: $caseTypeId")
    }
    if (!CaseTypeDraftHelper.isPublished(caseType)) {
        // No published definition exists, so the whole case type is the draft.
        return deleteCaseTypeDraft()
    }
    long activeVersion = caseType.activeDefinitionVersion as long
    long draftVersion = CaseTypeDraftHelper.draftVersion(caseType)
    deleteDefinitionRows(caseTypeId, draftVersion)
    deleteVersionAccessPolicy(caseTypeId, draftVersion)
    caseType.definitionVersion = activeVersion
    // Data written before the draft state was derived from versions may still say DRAFT.
    caseType.statusId = CaseTypeDraftHelper.ACTIVE
    GenericValue activePolicy = findVersionAccessPolicy(caseTypeId, activeVersion)
    if (activePolicy != null) {
        caseType.accessPolicyId = activePolicy.accessPolicyId
    }
    caseType.lastModifiedDate = UtilDateTime.nowTimestamp()
    caseType.store()
    return successMap([
            caseTypeId      : caseTypeId,
            definitionVersion: activeVersion,
            discardedVersion: draftVersion
    ])
}

private List<GenericValue> getStages(String caseTypeId, long version) {
    return from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).orderBy('stageSeq').queryList()
}

private void changeStageSeq(String caseTypeId, long version, long oldSeq, long newSeq) {
    GenericValue stage = from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: oldSeq
    ])).queryOne()
    if (stage == null) {
        return
    }
    List<GenericValue> steps = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: oldSeq
    ])).queryList()
    List<GenericValue> mappings = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: oldSeq
    ])).queryList()
    List<GenericValue> fieldLabels = from('CaseFieldLabel').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: oldSeq
    ])).queryList()
    List<GenericValue> serviceParams = from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: oldSeq
    ])).queryList()
    List<GenericValue> serviceOutputs = from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: oldSeq
    ])).queryList()
    List<GenericValue> stepForms = from('CaseStepForm').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: oldSeq
    ])).queryList()
    List<GenericValue> stepWaits = from('CaseStepWait').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: oldSeq
    ])).queryList()
    List<GenericValue> transitions = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).queryList().findAll { GenericValue transition ->
        (transition.stageSeq as long) == oldSeq ||
                (transition.toStageSeq != null && (transition.toStageSeq as long) == oldSeq)
    }
    transitions.each { GenericValue transition -> delegator.removeValue(transition) }
    mappings.each { GenericValue mapping -> delegator.removeValue(mapping) }
    fieldLabels.each { GenericValue label -> delegator.removeValue(label) }
    serviceParams.each { GenericValue mapping -> delegator.removeValue(mapping) }
    serviceOutputs.each { GenericValue mapping -> delegator.removeValue(mapping) }
    stepForms.each { GenericValue mapping -> delegator.removeValue(mapping) }
    stepWaits.each { GenericValue wait -> delegator.removeValue(wait) }
    steps.each { GenericValue step -> delegator.removeValue(step) }
    delegator.removeValue(stage)

    Map<String, Object> stageFields = copyFields(stage)
    stageFields.stageSeq = newSeq
    delegator.create('CaseStageDef', stageFields)
    steps.each { GenericValue step ->
        Map<String, Object> fields = copyFields(step)
        fields.stageSeq = newSeq
        delegator.create('CaseStepDef', fields)
    }
    mappings.each { GenericValue mapping ->
        Map<String, Object> fields = copyFields(mapping)
        fields.stageSeq = newSeq
        delegator.create('CaseFieldMapping', fields)
    }
    fieldLabels.each { GenericValue label ->
        Map<String, Object> fields = copyFields(label)
        fields.stageSeq = newSeq
        delegator.create('CaseFieldLabel', fields)
    }
    serviceParams.each { GenericValue mapping ->
        Map<String, Object> fields = copyFields(mapping)
        fields.stageSeq = newSeq
        delegator.create('CaseStepServiceParam', fields)
    }
    serviceOutputs.each { GenericValue mapping ->
        Map<String, Object> fields = copyFields(mapping)
        fields.stageSeq = newSeq
        delegator.create('CaseStepServiceOutput', fields)
    }
    stepForms.each { GenericValue mapping ->
        Map<String, Object> fields = copyFields(mapping)
        fields.stageSeq = newSeq
        delegator.create('CaseStepForm', fields)
    }
    stepWaits.each { GenericValue wait ->
        Map<String, Object> fields = copyFields(wait)
        fields.stageSeq = newSeq
        delegator.create('CaseStepWait', fields)
    }
    transitions.each { GenericValue transition ->
        Map<String, Object> fields = copyFields(transition)
        if ((transition.stageSeq as long) == oldSeq) {
            fields.stageSeq = newSeq
        }
        if (transition.toStageSeq != null && (transition.toStageSeq as long) == oldSeq) {
            fields.toStageSeq = newSeq
        }
        delegator.create('CaseStepTransition', fields)
    }
}

private void changeStepSeq(String caseTypeId, long version, long stageSeq, long oldSeq, long newSeq) {
    GenericValue step = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: oldSeq
    ])).queryOne()
    if (step == null) {
        return
    }
    List<GenericValue> mappings = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: oldSeq
    ])).queryList()
    List<GenericValue> fieldLabels = from('CaseFieldLabel').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: oldSeq
    ])).queryList()
    List<GenericValue> serviceParams = from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: oldSeq
    ])).queryList()
    List<GenericValue> serviceOutputs = from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: oldSeq
    ])).queryList()
    List<GenericValue> stepForms = from('CaseStepForm').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: oldSeq
    ])).queryList()
    List<GenericValue> stepWaits = from('CaseStepWait').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version, stageSeq: stageSeq, stepSeq: oldSeq
    ])).queryList()
    List<GenericValue> transitions = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).queryList().findAll { GenericValue transition ->
        ((transition.stageSeq as long) == stageSeq && (transition.stepSeq as long) == oldSeq) ||
                (transition.toStageSeq != null && (transition.toStageSeq as long) == stageSeq &&
                        transition.toStepSeq != null && (transition.toStepSeq as long) == oldSeq)
    }
    transitions.each { GenericValue transition -> delegator.removeValue(transition) }
    mappings.each { GenericValue mapping -> delegator.removeValue(mapping) }
    fieldLabels.each { GenericValue label -> delegator.removeValue(label) }
    serviceParams.each { GenericValue mapping -> delegator.removeValue(mapping) }
    serviceOutputs.each { GenericValue mapping -> delegator.removeValue(mapping) }
    stepForms.each { GenericValue mapping -> delegator.removeValue(mapping) }
    stepWaits.each { GenericValue wait -> delegator.removeValue(wait) }
    delegator.removeValue(step)

    Map<String, Object> stepFields = copyFields(step)
    stepFields.stepSeq = newSeq
    stepFields.sequenceNum = newSeq
    delegator.create('CaseStepDef', stepFields)
    mappings.each { GenericValue mapping ->
        Map<String, Object> fields = copyFields(mapping)
        fields.stepSeq = newSeq
        delegator.create('CaseFieldMapping', fields)
    }
    fieldLabels.each { GenericValue label ->
        Map<String, Object> fields = copyFields(label)
        fields.stepSeq = newSeq
        delegator.create('CaseFieldLabel', fields)
    }
    serviceParams.each { GenericValue mapping ->
        Map<String, Object> fields = copyFields(mapping)
        fields.stepSeq = newSeq
        delegator.create('CaseStepServiceParam', fields)
    }
    serviceOutputs.each { GenericValue mapping ->
        Map<String, Object> fields = copyFields(mapping)
        fields.stepSeq = newSeq
        delegator.create('CaseStepServiceOutput', fields)
    }
    stepForms.each { GenericValue mapping ->
        Map<String, Object> fields = copyFields(mapping)
        fields.stepSeq = newSeq
        delegator.create('CaseStepForm', fields)
    }
    stepWaits.each { GenericValue wait ->
        Map<String, Object> fields = copyFields(wait)
        fields.stepSeq = newSeq
        delegator.create('CaseStepWait', fields)
    }
    transitions.each { GenericValue transition ->
        Map<String, Object> fields = copyFields(transition)
        if ((transition.stageSeq as long) == stageSeq && (transition.stepSeq as long) == oldSeq) {
            fields.stepSeq = newSeq
        }
        if (transition.toStageSeq != null && (transition.toStageSeq as long) == stageSeq &&
                transition.toStepSeq != null && (transition.toStepSeq as long) == oldSeq) {
            fields.toStepSeq = newSeq
        }
        delegator.create('CaseStepTransition', fields)
    }
}

private void changeTransitionSeq(String caseTypeId, long version, long stageSeq, long stepSeq,
                                 String outcomeEnumId, long oldSeq, long newSeq) {
    GenericValue transition = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId,
            definitionVersion: version,
            stageSeq: stageSeq,
            stepSeq: stepSeq,
            outcomeEnumId: outcomeEnumId,
            transitionSeq: oldSeq
    ])).queryOne()
    if (transition != null) {
        Map<String, Object> fields = copyFields(transition)
        fields.transitionSeq = newSeq
        delegator.removeValue(transition)
        delegator.create('CaseStepTransition', fields)
    }
}

private Map<String, Object> copyFields(GenericValue value) {
    Map<String, Object> fields = new HashMap<>(value.getAllFields())
    ['createdStamp', 'createdTxStamp', 'lastUpdatedStamp', 'lastUpdatedTxStamp'].each { String field ->
        fields.remove(field)
    }
    return fields
}

private Map checkDraft(String caseTypeId, Object version) {
    GenericValue caseType = from('CaseType').where('caseTypeId', caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: $caseTypeId")
    }
    if (!CaseTypeDraftHelper.hasEditableDraft(caseType)) {
        return errorMap("Case type is not a draft: $caseTypeId")
    }
    long expected = (caseType.definitionVersion ?: 1) as long
    long requested = (version ?: expected) as long
    if (requested != expected) {
        return errorMap("Definition version mismatch: expected $expected, got $requested")
    }
    return successMap([ok: true])
}

private List<String> validateDefinition(GenericValue caseType, long version) {
    List<String> errors = []
    List<GenericValue> stages = from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).orderBy('stageSeq').queryList()
    if (stages.isEmpty()) {
        errors << 'At least one stage is required'
        return errors
    }
    if (stages[0].stageSeq as long != 1L) {
        errors << 'Stage 1 is required'
    }
    Set<Long> stageSeqs = stages.collect { GenericValue stage -> stage.stageSeq as long } as Set<Long>
    stages.each { GenericValue stage ->
        List<GenericValue> steps = from('CaseStepDef').where(EntityCondition.makeCondition([
                caseTypeId: caseType.caseTypeId, definitionVersion: version, stageSeq: stage.stageSeq
        ])).orderBy('stepSeq').queryList()
        if (steps.isEmpty() && stage.autoAdvance != 'Y') {
            errors << "Stage ${stage.stageSeq} has no steps and autoAdvance is not Y"
        }
        if (stage.autoAdvance != 'Y' &&
                steps.any { GenericValue step -> ['CASE_STEP_SERVICE', 'CASE_STEP_DECISION'].contains(step.stepTypeEnumId) }) {
            errors << "Stage ${stage.stageSeq} has Service/Decision steps and autoAdvance is not Y"
        }
        steps.each { GenericValue step ->
            if (!step.stepName) {
                errors << "Step ${stage.stageSeq}/${step.stepSeq} has no name"
            }
            if (['CASE_STEP_SERVICE', 'CASE_STEP_DECISION'].contains(step.stepTypeEnumId) && !step.serviceName) {
                errors << "Step ${stage.stageSeq}/${step.stepSeq} has no service"
            }
            if (step.assignmentPolicyId &&
                    from('CaseAssignmentPolicy').where('assignmentPolicyId', step.assignmentPolicyId).queryOne() == null) {
                errors << "Assignment policy not found: ${step.assignmentPolicyId}"
            }
            if (step.slaId && from('CaseSla').where('slaId', step.slaId).queryOne() == null) {
                errors << "SLA not found: ${step.slaId}"
            }
            if (step.stepTypeEnumId == 'CASE_STEP_WAIT') {
                GenericValue wait = from('CaseStepWait').where(EntityCondition.makeCondition([
                        caseTypeId       : caseType.caseTypeId,
                        definitionVersion: version,
                        stageSeq         : step.stageSeq,
                        stepSeq          : step.stepSeq
                ])).queryOne()
                if (wait == null) {
                    errors << "Wait step ${stage.stageSeq}/${step.stepSeq} has no wait settings"
                } else {
                    long timeoutDuration = (wait.timeoutDuration ?: 0) as long
                    if (!wait.signalName && timeoutDuration <= 0) {
                        errors << "Wait step ${stage.stageSeq}/${step.stepSeq} requires a signal name or timeout"
                    }
                    if (timeoutDuration > 0) {
                        if (!wait.timeoutOutcomeEnumId) {
                            errors << "Wait step ${stage.stageSeq}/${step.stepSeq} requires a timeout outcome"
                        } else if (from('Enumeration').where(EntityCondition.makeCondition([
                                enumTypeId: 'CASE_OUTCOME',
                                enumId    : wait.timeoutOutcomeEnumId
                        ])).queryOne() == null) {
                            errors << "Timeout outcome not found: ${wait.timeoutOutcomeEnumId}"
                        }
                    }
                }
            }
            if (step.stepTypeEnumId == 'CASE_STEP_SUB_CASE') {
                String childCaseTypeId = step.subCaseTypeId
                if (!childCaseTypeId) {
                    GenericValue subCaseTypeAttr = from('CaseTypeAttr').where(EntityCondition.makeCondition([
                            caseTypeId: caseType.caseTypeId,
                            attrName  : 'SUB_CASE_TYPE'
                    ])).queryOne()
                    childCaseTypeId = subCaseTypeAttr?.attrValue
                }
                if (!childCaseTypeId) {
                    errors << "Sub-case step ${stage.stageSeq}/${step.stepSeq} has no child case type"
                } else {
                    GenericValue childCaseType = from('CaseType').where('caseTypeId', childCaseTypeId).queryOne()
                    if (childCaseType == null) {
                        errors << "Sub-case type not found: $childCaseTypeId"
                    } else if (childCaseType.statusId != 'CASE_TYPE_ACTIVE') {
                        errors << "Sub-case type is not active: $childCaseTypeId"
                    }
                }
            }
        }
    }
    List<GenericValue> transitions = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: caseType.caseTypeId, definitionVersion: version
    ])).queryList()
    transitions.each { GenericValue transition ->
        if (transition.toStageSeq != null && (transition.toStageSeq as long) > 0 &&
                !stageSeqs.contains(transition.toStageSeq as long)) {
            errors << "Transition target stage not found: ${transition.toStageSeq}"
        }
        if (transition.toStageSeq != null && transition.toStepSeq != null &&
                (transition.toStageSeq as long) > 0 && (transition.toStepSeq as long) > 0) {
            GenericValue targetStep = from('CaseStepDef').where(EntityCondition.makeCondition([
                    caseTypeId: caseType.caseTypeId,
                    definitionVersion: version,
                    stageSeq: transition.toStageSeq,
                    stepSeq: transition.toStepSeq
            ])).queryOne()
            if (targetStep == null) {
                errors << "Transition target step not found: ${transition.toStageSeq}/${transition.toStepSeq}"
            }
        }
    }
    GenericValue validationPolicy = findVersionAccessPolicy(caseType.caseTypeId, version) ?: findAccessPolicy(caseType)
    if (validationPolicy != null) {
        from('CaseAccessRule').where('accessPolicyId', validationPolicy.accessPolicyId).queryList().each { GenericValue rule ->
            if (rule.stageSeq != null && !stageSeqs.contains(rule.stageSeq as long)) {
                errors << "Access rule ${rule.ruleSeq} references stage not found: ${rule.stageSeq}"
            }
            if (rule.stageSeq != null && rule.stepSeq != null &&
                    from('CaseStepDef').where(EntityCondition.makeCondition([
                            caseTypeId       : caseType.caseTypeId,
                            definitionVersion: version,
                            stageSeq         : rule.stageSeq,
                            stepSeq          : rule.stepSeq
                    ])).queryOne() == null) {
                errors << "Access rule ${rule.ruleSeq} references step not found: ${rule.stageSeq}/${rule.stepSeq}"
            }
            if (rule.permissionEnumId && from('Enumeration').where(EntityCondition.makeCondition([
                    enumTypeId: 'CASE_ACCESS_PERM',
                    enumId    : rule.permissionEnumId
            ])).queryOne() == null) {
                errors << "Access rule ${rule.ruleSeq} has unknown permission: ${rule.permissionEnumId}"
            }
            if (rule.queueId && from('CaseQueue').where('queueId', rule.queueId).queryOne() == null) {
                errors << "Access rule ${rule.ruleSeq} has unknown queue: ${rule.queueId}"
            }
            if (rule.partyId && from('Party').where('partyId', rule.partyId).queryOne() == null) {
                errors << "Access rule ${rule.ruleSeq} has unknown party: ${rule.partyId}"
            }
        }
    }
    return errors
}

private void cloneDefinition(String caseTypeId, long fromVersion, long toVersion) {
    ['CaseStageDef', 'CaseStepDef', 'CaseStepWait', 'CaseStepTransition', 'CaseFieldMapping',
     'CaseFieldLabel', 'CaseStepServiceParam', 'CaseStepServiceOutput', 'CaseTypeStartForm', 'CaseStepForm'].each { String entityName ->
        List<GenericValue> values = from(entityName).where(EntityCondition.makeCondition([
                caseTypeId: caseTypeId, definitionVersion: fromVersion
        ])).queryList()
        values.each { GenericValue value ->
            Map<String, Object> copy = new HashMap<>(value.getAllFields())
            ['createdStamp', 'createdTxStamp', 'lastUpdatedStamp', 'lastUpdatedTxStamp'].each { String field ->
                copy.remove(field)
            }
            copy.put('definitionVersion', toVersion)
            delegator.create(entityName, copy)
        }
    }
}

private Map successMap(Map values) {
    Map result = ServiceUtil.returnSuccess()
    result.putAll(values)
    return result
}

private Map errorMap(String message) {
    return ServiceUtil.returnError(message)
}

// ---------------------------------------------------------------------------
// VE-P2: visual editor batch save
// ---------------------------------------------------------------------------

Map saveCaseTypeDefinitionDraft() {
    String caseTypeId = parameters.caseTypeId
    Long requestedVersion = parameters.definitionVersion == null ? null : parameters.definitionVersion as Long
    if (!caseTypeId) {
        return errorMap('caseTypeId is required')
    }
    if (requestedVersion == null) {
        return errorMap('definitionVersion is required')
    }
    if (!parameters.document) {
        return errorMap('document is required')
    }
    GenericValue caseType = lockCaseType(caseTypeId)
    if (caseType == null) {
        return errorMap("Case type not found: $caseTypeId")
    }
    if (!CaseTypeDraftHelper.hasEditableDraft(caseType)) {
        return errorMap("Case type is not a draft: $caseTypeId")
    }
    long version = caseType.definitionVersion as long
    if (version != requestedVersion) {
        return errorMap("Definition version mismatch: expected $version, got $requestedVersion")
    }

    Map document
    try {
        document = new JsonSlurper().parseText(parameters.document as String) as Map
    } catch (Exception e) {
        return errorMap("Invalid document JSON: ${e.message}")
    }
    if (!(document.stages instanceof List)) {
        return errorMap('document.stages must be a list')
    }

    String currentRevision = computeCaseTypeDefinitionRevision(caseTypeId, version)
    String expectedRevision = parameters.expectedRevision
    if (!expectedRevision || expectedRevision != currentRevision) {
        Map result = errorMap(expectedRevision
                ? 'Case type definition was changed by another user'
                : 'expectedRevision is required to save the definition')
        result.errorType = 'CONFLICT'
        result.currentRevision = currentRevision
        return result
    }

    GenericValue oldStartForm = from('CaseTypeStartForm').where(EntityCondition.makeCondition([
            caseTypeId       : caseTypeId,
            definitionVersion: version
    ])).queryOne()

    deleteDefinitionRows(caseTypeId, version)

    // Header fields are not versioned: running cases read them from CaseType, so only a case type
    // that was never published may change them here. The access policy is never taken from the
    // document; it is resolved from the version link (CaseTypeAccessPolicy).
    Map caseTypeInput = document.caseType instanceof Map ? document.caseType as Map : [:]
    if (!CaseTypeDraftHelper.isPublished(caseType)) {
        ['caseTypeName', 'description', 'primaryEntityName', 'extensionEntityName', 'extensionFormId',
         'defaultSlaId'].each { String field ->
            if (caseTypeInput.containsKey(field)) {
                caseType.set(field, caseTypeInput[field])
            }
        }
    }

    Map<String, Long> oldStageToNew = [:]
    Map<String, Long> oldStepToNew = [:]
    Map<String, Long> stageClientToSeq = [:]
    Map<String, List<Long>> stepClientToKey = [:]
    List<Map> stages = document.stages as List
    stages.eachWithIndex { Map stageInput, int stageIndex ->
        long stageSeq = stageIndex + 1L
        Long oldStageSeq = stageInput.key instanceof Map ? toLong((stageInput.key as Map).stageSeq) : null
        if (oldStageSeq != null) {
            oldStageToNew[oldStageSeq.toString()] = stageSeq
        }
        if (stageInput.clientId) {
            stageClientToSeq[stageInput.clientId.toString()] = stageSeq
        }
        delegator.create('CaseStageDef', [
                caseTypeId       : caseTypeId,
                definitionVersion: version,
                stageSeq         : stageSeq,
                stageName        : stageInput.stageName ?: "Stage $stageSeq",
                stageTypeEnumId  : stageInput.stageTypeEnumId ?: 'CASE_STAGE_PRIMARY',
                autoAdvance      : stageInput.autoAdvance ?: 'N',
                entryServiceName : stageInput.entryServiceName,
                exitServiceName  : stageInput.exitServiceName,
                description      : stageInput.description
        ])
        List<Map> stepInputs = stageInput.steps instanceof List ? stageInput.steps as List : []
        stepInputs.eachWithIndex { Map stepInput, int stepIndex ->
            long stepSeq = stepIndex + 1L
            Long oldStepSeq = stepInput.key instanceof Map ? toLong((stepInput.key as Map).stepSeq) : null
            if (oldStageSeq != null && oldStepSeq != null) {
                oldStepToNew["${oldStageSeq}:${oldStepSeq}".toString()] = stepSeq
            }
            if (stepInput.clientId) {
                stepClientToKey[stepInput.clientId.toString()] = [stageSeq, stepSeq]
            }
            createStepFromDocument(caseTypeId, version, stageSeq, stepSeq, stepInput)
        }
        createStageTransitions(caseTypeId, version, stageSeq, stepInputs, stageInput)
    }

    boolean startFormProvided = document.containsKey('startForm')
    Map startFormInput = document.startForm instanceof Map ? document.startForm as Map : null
    if (!startFormProvided && oldStartForm != null) {
        startFormInput = [
                formLocation     : oldStartForm.formLocation,
                formName         : oldStartForm.formName,
                formTarget       : oldStartForm.formTarget,
                startServiceName : oldStartForm.startServiceName,
                submitLabel      : oldStartForm.submitLabel,
                description      : oldStartForm.description
        ]
    }
    if (startFormInput != null && !startFormInput.values().any { Object value ->
        value != null && value.toString().trim()
    }) {
        startFormInput = null
    }
    createStartFormFromDocument(caseTypeId, version, startFormInput)

    boolean accessPolicyProvided = document.accessPolicy instanceof Map
    List<Map> accessRuleInput = document.accessRules instanceof List ? document.accessRules as List : null
    boolean accessRulesProvided = accessRuleInput != null
    boolean accessNeedsUpdate = accessPolicyProvided || (accessRulesProvided && !accessRuleInput.isEmpty())
    GenericValue versionAccessPolicy = findVersionAccessPolicy(caseTypeId, version)
    List<String> accessErrors = []
    if (accessNeedsUpdate) {
        versionAccessPolicy = upsertAccessPolicy(caseType, document, version)
        if (accessRulesProvided) {
            accessErrors = replaceAccessRules(versionAccessPolicy, accessRuleInput, stageClientToSeq, stepClientToKey)
        } else if (versionAccessPolicy != null) {
            accessErrors = remapAccessRules(versionAccessPolicy, oldStageToNew, oldStepToNew)
        }
    } else if (versionAccessPolicy != null) {
        accessErrors = remapAccessRules(versionAccessPolicy, oldStageToNew, oldStepToNew)
    }

    List<String> validationErrors = accessErrors + validateDefinition(caseType, version)
    if (!validationErrors.isEmpty()) {
        TransactionUtil.setRollbackOnly('Case type definition validation failed', null)
        Map result = errorMap('Case type definition is invalid')
        result.validationErrors = validationErrors
        return result
    }
    caseType.lastModifiedDate = UtilDateTime.nowTimestamp()
    caseType.store()

    String newRevision = computeCaseTypeDefinitionRevision(caseTypeId, version)
    return successMap([
            revision       : newRevision,
            saved          : true,
            validationErrors: []
    ])
}

private void deleteDefinitionRows(String caseTypeId, long version) {
    ['CaseStepTransition', 'CaseStepServiceOutput', 'CaseStepServiceParam', 'CaseFieldLabel',
     'CaseFieldMapping', 'CaseStepForm', 'CaseStepWait', 'CaseStepDef', 'CaseStageDef',
     'CaseTypeStartForm'].each { String entityName ->
        from(entityName).where(EntityCondition.makeCondition([
                caseTypeId       : caseTypeId,
                definitionVersion: version
        ])).queryList().each { GenericValue value -> delegator.removeValue(value) }
    }
}

private void createStepFromDocument(String caseTypeId, long version, long stageSeq, long stepSeq, Map stepInput) {
    String stepTypeEnumId = stepInput.stepTypeEnumId ?: 'CASE_STEP_HUMAN_TASK'
    delegator.create('CaseStepDef', [
            caseTypeId         : caseTypeId,
            definitionVersion  : version,
            stageSeq           : stageSeq,
            stepSeq            : stepSeq,
            stepName           : stepInput.stepName ?: "Step $stepSeq",
            stepTypeEnumId     : stepTypeEnumId,
            serviceName        : stepInput.serviceName,
            formId             : stepInput.formId,
            subCaseTypeId      : stepInput.subCaseTypeId,
            assignmentPolicyId : stepInput.assignmentPolicyId,
            slaId              : stepInput.slaId,
            requiredIndicator  : stepInput.requiredIndicator ?: 'Y',
            sequenceNum        : stepInput.sequenceNum != null ? (stepInput.sequenceNum as long) : stepSeq,
            description        : stepInput.description
    ])

    Map waitInput = stepInput.wait instanceof Map ? stepInput.wait as Map : null
    if (waitInput != null && (stepTypeEnumId == 'CASE_STEP_WAIT'
            || waitInput.signalName || waitInput.timeoutDuration || waitInput.timeoutOutcomeEnumId)) {
        delegator.create('CaseStepWait', [
                caseTypeId          : caseTypeId,
                definitionVersion   : version,
                stageSeq            : stageSeq,
                stepSeq             : stepSeq,
                signalName          : waitInput.signalName,
                timeoutDuration     : toLong(waitInput.timeoutDuration),
                timeoutOutcomeEnumId: waitInput.timeoutOutcomeEnumId,
                description         : waitInput.description
        ])
    }

    Map formInput = stepInput.form instanceof Map ? stepInput.form as Map : null
    if (formInput != null) {
        delegator.create('CaseStepForm', [
                caseTypeId         : caseTypeId,
                definitionVersion  : version,
                stageSeq           : stageSeq,
                stepSeq            : stepSeq,
                formLocation       : formInput.formLocation,
                formName           : formInput.formName,
                formTarget         : formInput.formTarget,
                completeServiceName: formInput.completeServiceName,
                description        : formInput.description
        ])
    }

    List<Map> params = stepInput.serviceParams instanceof List ? stepInput.serviceParams as List : []
    params.eachWithIndex { Map param, int index ->
        delegator.create('CaseStepServiceParam', [
                caseTypeId       : caseTypeId,
                definitionVersion: version,
                stageSeq         : stageSeq,
                stepSeq          : stepSeq,
                paramSeq         : index + 1L,
                paramName        : param.paramName,
                sourceTypeEnumId : param.sourceTypeEnumId,
                sourceValue      : param.sourceValue,
                requiredIndicator: param.requiredIndicator ?: 'N',
                description      : param.description
        ])
    }

    List<Map> outputs = stepInput.serviceOutputs instanceof List ? stepInput.serviceOutputs as List : []
    outputs.eachWithIndex { Map output, int index ->
        delegator.create('CaseStepServiceOutput', [
                caseTypeId         : caseTypeId,
                definitionVersion  : version,
                stageSeq           : stageSeq,
                stepSeq            : stepSeq,
                outputSeq          : index + 1L,
                outputName         : output.outputName,
                variableName       : output.variableName,
                valueTypeEnumId    : output.valueTypeEnumId,
                requiredIndicator  : output.requiredIndicator ?: 'N',
                sensitiveIndicator : output.sensitiveIndicator ?: 'N',
                scopeEnumId        : output.scopeEnumId,
                ttlSeconds         : toLong(output.ttlSeconds),
                description        : output.description
        ])
    }

    List<Map> fields = stepInput.fieldMappings instanceof List ? stepInput.fieldMappings as List : []
    fields.eachWithIndex { Map field, int index ->
        long fieldSeq = index + 1L
        delegator.create('CaseFieldMapping', [
                caseTypeId         : caseTypeId,
                definitionVersion  : version,
                stageSeq           : stageSeq,
                stepSeq            : stepSeq,
                fieldSeq           : fieldSeq,
                fieldName          : field.fieldName,
                entityName         : field.entityName ?: 'Case',
                entityFieldName    : field.entityFieldName ?: field.fieldName,
                label              : field.label ?: field.fieldName,
                fieldTypeEnumId    : field.fieldTypeEnumId ?: 'CASE_FIELD_TEXT',
                requiredIndicator  : field.requiredIndicator ?: 'N',
                sequenceNum        : field.sequenceNum != null ? (field.sequenceNum as long) : fieldSeq,
                lookupEntityName   : field.lookupEntityName,
                lookupFieldName    : field.lookupFieldName,
                defaultValue       : field.defaultValue,
                visibilityCondition: field.visibilityCondition,
                readOnlyCondition  : field.readOnlyCondition,
                description        : field.description
        ])
        List<Map> fieldLabels = field.labels instanceof List ? field.labels as List : []
        fieldLabels.each { Map label ->
            if (label.locale) {
                delegator.create('CaseFieldLabel', [
                        caseTypeId       : caseTypeId,
                        definitionVersion: version,
                        stageSeq         : stageSeq,
                        stepSeq          : stepSeq,
                        fieldSeq         : fieldSeq,
                        locale           : label.locale,
                        label            : label.label,
                        description      : label.description
                ])
            }
        }
    }
}

private void createStageTransitions(String caseTypeId, long version, long stageSeq,
                                    List<Map> stepInputs, Map stageInput) {
    String mode = stageInput.transitionMode ?: 'UNIFORM'
    if ('RAW'.equals(mode)) {
        List<Map> transitions = stageInput.transitions instanceof List ? stageInput.transitions as List : []
        Map<String, Long> seqByKey = [:]
        transitions.each { Map transition ->
            long stepSeq = transition.stepSeq != null ? transition.stepSeq as long : 1L
            String outcomeEnumId = transition.outcomeEnumId ?: 'CASE_OUT_COMPLETE'
            String key = "${stepSeq}:${outcomeEnumId}".toString()
            long transitionSeq = (seqByKey[key] ?: 0L) + 1L
            seqByKey[key] = transitionSeq
            createTransition(caseTypeId, version, stageSeq, stepSeq, outcomeEnumId, transition, transitionSeq)
        }
        return
    }

    List<Map> exits = stageInput.exits instanceof List ? stageInput.exits as List : []
    if (exits.isEmpty()) {
        return
    }
    stepInputs.eachWithIndex { Map stepInput, int index ->
        long stepSeq = index + 1L
        Map<String, Long> seqByKey = [:]
        exits.each { Map exit ->
            String outcomeEnumId = exit.outcomeEnumId ?: 'CASE_OUT_COMPLETE'
            long transitionSeq = (seqByKey[outcomeEnumId] ?: 0L) + 1L
            seqByKey[outcomeEnumId] = transitionSeq
            createTransition(caseTypeId, version, stageSeq, stepSeq, outcomeEnumId, exit, transitionSeq)
        }
    }
}

private void createTransition(String caseTypeId, long version, long stageSeq, long stepSeq,
                              String outcomeEnumId, Map input, long transitionSeq) {
    Long toStageSeq = toLong(input.toStageSeq)
    Long toStepSeq = toLong(input.toStepSeq)
    if (toStageSeq != null && toStepSeq == null) {
        toStepSeq = 1L
    }
    if (toStageSeq == null) {
        toStepSeq = null
    }
    delegator.create('CaseStepTransition', [
            caseTypeId          : caseTypeId,
            definitionVersion   : version,
            stageSeq            : stageSeq,
            stepSeq             : stepSeq,
            outcomeEnumId       : outcomeEnumId,
            transitionSeq       : transitionSeq,
            toStageSeq          : toStageSeq,
            toStepSeq           : toStepSeq,
            conditionServiceName: input.conditionServiceName,
            priority            : input.priority != null ? (input.priority as long) : 0L,
            isDefault           : input.isDefault ?: 'N',
            description         : input.description
    ])
}

private void createStartFormFromDocument(String caseTypeId, long version, Map startForm) {
    if (startForm == null) {
        return
    }
    delegator.create('CaseTypeStartForm', [
            caseTypeId       : caseTypeId,
            definitionVersion: version,
            formLocation     : startForm.formLocation,
            formName         : startForm.formName,
            formTarget       : startForm.formTarget,
            startServiceName : startForm.startServiceName,
            submitLabel      : startForm.submitLabel,
            description      : startForm.description
    ])
}

private GenericValue upsertAccessPolicy(GenericValue caseType, Map document, long version) {
    Map policyInput = document.accessPolicy instanceof Map ? document.accessPolicy as Map : [:]
    GenericValue policy = findVersionAccessPolicy(caseType.caseTypeId, version)
    long activeVersion = (caseType.activeDefinitionVersion ?: 0) as long
    boolean activeOrUnpublished = activeVersion == 0 || version == activeVersion
    if (policy == null) {
        GenericValue sourcePolicy = findAccessPolicy(caseType)
        if (sourcePolicy != null && activeOrUnpublished) {
            policy = sourcePolicy
        } else if (sourcePolicy != null) {
            // A draft with no version link is legacy data. Copy the published
            // policy so edits cannot leak into the ACTIVE version.
            policy = copyAccessPolicy(caseType.caseTypeId, sourcePolicy)
        }
        if (policy == null) {
            policy = createAccessPolicy(caseType.caseTypeId, policyInput.description)
        }
    }
    if (!ownsAccessPolicy(policy, caseType.caseTypeId, version)) {
        // The rules are replaced below; never edit a policy of another case type or version.
        policy = copyAccessPolicy(caseType.caseTypeId, policy)
    }
    ensureVersionAccessPolicy(caseType.caseTypeId, version, policy.accessPolicyId)
    if (policyInput.containsKey('description')) {
        policy.description = policyInput.description
        policy.store()
    }
    if (activeOrUnpublished) {
        caseType.accessPolicyId = policy.accessPolicyId
    }
    return policy
}

/** True when only this case type version uses the policy, so its rules may be replaced. */
private boolean ownsAccessPolicy(GenericValue policy, String caseTypeId, long version) {
    if (policy == null || policy.caseTypeId != caseTypeId) {
        return false
    }
    boolean linkedElsewhere = from('CaseTypeAccessPolicy').where('accessPolicyId', policy.accessPolicyId).queryList()
            .any { GenericValue link -> link.caseTypeId != caseTypeId || (link.definitionVersion as long) != version }
    if (linkedElsewhere) {
        return false
    }
    boolean usedByOtherType = from('CaseType').where('accessPolicyId', policy.accessPolicyId).queryList()
            .any { GenericValue other -> other.caseTypeId != caseTypeId }
    if (usedByOtherType) {
        return false
    }
    return from('Case').where('accessPolicyId', policy.accessPolicyId).queryFirst() == null
}

private GenericValue findVersionAccessPolicy(String caseTypeId, long version) {
    GenericValue link = from('CaseTypeAccessPolicy').where(EntityCondition.makeCondition([
            caseTypeId       : caseTypeId,
            definitionVersion: version
    ])).queryOne()
    if (link == null) {
        return null
    }
    return from('CaseAccessPolicy').where('accessPolicyId', link.accessPolicyId).queryOne()
}

private void ensureVersionAccessPolicy(String caseTypeId, long version, String accessPolicyId) {
    if (!accessPolicyId) {
        return
    }
    GenericValue link = from('CaseTypeAccessPolicy').where(EntityCondition.makeCondition([
            caseTypeId       : caseTypeId,
            definitionVersion: version
    ])).queryOne()
    if (link == null) {
        delegator.create('CaseTypeAccessPolicy', [
                caseTypeId       : caseTypeId,
                definitionVersion: version,
                accessPolicyId   : accessPolicyId
        ])
    } else if (link.accessPolicyId != accessPolicyId) {
        link.accessPolicyId = accessPolicyId
        link.store()
    }
}

private void cloneVersionAccessPolicy(GenericValue caseType, long fromVersion, long toVersion) {
    // A failed run can leave a stale policy link for the target version.
    deleteVersionAccessPolicy(caseType.caseTypeId, toVersion)
    GenericValue sourcePolicy = findVersionAccessPolicy(caseType.caseTypeId, fromVersion)
    if (sourcePolicy == null) {
        sourcePolicy = findAccessPolicy(caseType)
    }
    if (sourcePolicy == null) {
        return
    }
    ensureVersionAccessPolicy(caseType.caseTypeId, fromVersion, sourcePolicy.accessPolicyId)
    GenericValue draftPolicy = copyAccessPolicy(caseType.caseTypeId, sourcePolicy)
    ensureVersionAccessPolicy(caseType.caseTypeId, toVersion, draftPolicy.accessPolicyId)
}

private GenericValue createAccessPolicy(String caseTypeId, String description) {
    String newPolicyId = 'AP' + delegator.getNextSeqId('CaseAccessPolicy')
    return delegator.create('CaseAccessPolicy', [
            accessPolicyId: newPolicyId,
            caseTypeId    : caseTypeId,
            description   : description ?: "Access policy for ${caseTypeId}"
    ])
}

private GenericValue copyAccessPolicy(String caseTypeId, GenericValue sourcePolicy) {
    if (sourcePolicy == null) {
        return null
    }
    GenericValue copy = createAccessPolicy(caseTypeId, sourcePolicy.description)
    from('CaseAccessRule').where('accessPolicyId', sourcePolicy.accessPolicyId).orderBy('ruleSeq').queryList()
            .each { GenericValue rule ->
                delegator.create('CaseAccessRule', [
                        accessPolicyId      : copy.accessPolicyId,
                        ruleSeq             : rule.ruleSeq,
                        partyId             : rule.partyId,
                        partyRoleTypeId     : rule.partyRoleTypeId,
                        queueId             : rule.queueId,
                        permissionEnumId    : rule.permissionEnumId,
                        stageSeq            : rule.stageSeq,
                        stepSeq             : rule.stepSeq,
                        conditionServiceName: rule.conditionServiceName,
                        description         : rule.description
                ])
            }
    return copy
}

private void deleteVersionAccessPolicy(String caseTypeId, long version) {
    GenericValue link = from('CaseTypeAccessPolicy').where(EntityCondition.makeCondition([
            caseTypeId       : caseTypeId,
            definitionVersion: version
    ])).queryOne()
    if (link == null) {
        return
    }
    String policyId = link.accessPolicyId
    delegator.removeValue(link)
    deleteAccessPolicyIfUnused(policyId)
}

private void deleteAllAccessPolicies(GenericValue caseType) {
    Set<String> policyIds = new LinkedHashSet<>()
    from('CaseTypeAccessPolicy').where('caseTypeId', caseType.caseTypeId).queryList().each { GenericValue link ->
        policyIds << link.accessPolicyId
        delegator.removeValue(link)
    }
    if (caseType.accessPolicyId) {
        policyIds << caseType.accessPolicyId
        caseType.accessPolicyId = null
        caseType.store()
    }
    policyIds.each { String policyId -> deleteAccessPolicyIfUnused(policyId) }
}

private void deleteAccessPolicyIfUnused(String policyId) {
    if (!policyId) {
        return
    }
    if (from('CaseTypeAccessPolicy').where('accessPolicyId', policyId).queryFirst() != null) {
        return
    }
    if (from('Case').where('accessPolicyId', policyId).queryFirst() != null) {
        return
    }
    if (from('CaseType').where('accessPolicyId', policyId).queryFirst() != null) {
        return
    }
    from('CaseAccessRule').where('accessPolicyId', policyId).queryList()
            .each { GenericValue value -> delegator.removeValue(value) }
    GenericValue policy = from('CaseAccessPolicy').where('accessPolicyId', policyId).queryOne()
    if (policy != null) {
        delegator.removeValue(policy)
    }
}

/**
 * Replaces the rules of the policy. A rule whose stage or step no longer exists is reported instead
 * of saved: an empty scope means "every stage" and would widen the rule.
 */
private List<String> replaceAccessRules(GenericValue policy, List<Map> rules, Map<String, Long> stageClientToSeq,
                                        Map<String, List<Long>> stepClientToKey) {
    List<String> errors = []
    if (policy == null) {
        return errors
    }
    from('CaseAccessRule').where('accessPolicyId', policy.accessPolicyId).queryList()
            .each { GenericValue value -> delegator.removeValue(value) }
    Set<Long> stageSeqs = new HashSet<>(stageClientToSeq.values())
    Set<String> stepKeys = stepClientToKey.values().collect { List<Long> key -> "${key[0]}:${key[1]}".toString() } as Set<String>
    long ruleSeq = 0L
    rules.eachWithIndex { Map rule, int index ->
        if (!rule.partyId && !rule.partyRoleTypeId && !rule.queueId && !rule.permissionEnumId) {
            return
        }
        Long stageSeq = null
        Long stepSeq = null
        boolean orphan = false
        if (rule.stageClientId) {
            stageSeq = stageClientToSeq[rule.stageClientId.toString()]
            orphan = stageSeq == null
        } else if (rule.stageSeq != null && rule.stageSeq != '') {
            stageSeq = toLong(rule.stageSeq)
            orphan = !stageSeqs.contains(stageSeq)
        }
        if (!orphan && rule.stepClientId) {
            List<Long> key = stepClientToKey[rule.stepClientId.toString()]
            orphan = key == null || key[0] != stageSeq
            stepSeq = key == null ? null : key[1]
        } else if (!orphan && rule.stepSeq != null && rule.stepSeq != '') {
            stepSeq = toLong(rule.stepSeq)
            orphan = stageSeq == null || !stepKeys.contains("${stageSeq}:${stepSeq}".toString())
        }
        if (orphan) {
            errors << "Access rule ${index + 1} refers to a stage or step that does not exist".toString()
            return
        }
        ruleSeq++
        delegator.create('CaseAccessRule', [
                accessPolicyId      : policy.accessPolicyId,
                ruleSeq             : ruleSeq,
                partyId             : rule.partyId,
                partyRoleTypeId     : rule.partyRoleTypeId,
                queueId             : rule.queueId,
                permissionEnumId    : rule.permissionEnumId,
                stageSeq            : stageSeq,
                stepSeq             : stageSeq == null ? null : stepSeq,
                conditionServiceName: rule.conditionServiceName,
                description         : rule.description
        ])
    }
    return errors
}

/** Follows renumbered stages and steps; a rule whose stage or step was removed is reported, not widened. */
private List<String> remapAccessRules(GenericValue policy, Map<String, Long> oldStageToNew,
                                      Map<String, Long> oldStepToNew) {
    List<String> errors = []
    if (policy == null) {
        return errors
    }
    from('CaseAccessRule').where('accessPolicyId', policy.accessPolicyId).orderBy('ruleSeq').queryList().each { GenericValue rule ->
        Long oldStageSeq = rule.stageSeq as Long
        Long oldStepSeq = rule.stepSeq as Long
        if (oldStageSeq == null) {
            return
        }
        Long newStageSeq = oldStageToNew[oldStageSeq.toString()]
        Long newStepSeq = oldStepSeq == null ? null : oldStepToNew["${oldStageSeq}:${oldStepSeq}".toString()]
        if (newStageSeq == null || (oldStepSeq != null && newStepSeq == null)) {
            errors << "Access rule ${rule.ruleSeq} refers to a stage or step that was removed".toString()
            return
        }
        rule.stageSeq = newStageSeq
        rule.stepSeq = newStepSeq
        rule.store()
    }
    return errors
}

private GenericValue findAccessPolicy(GenericValue caseType) {
    if (caseType.accessPolicyId) {
        GenericValue policy = from('CaseAccessPolicy').where('accessPolicyId', caseType.accessPolicyId).queryOne()
        if (policy != null) {
            return policy
        }
    }
    return from('CaseAccessPolicy').where('caseTypeId', caseType.caseTypeId).queryFirst()
}

private String computeCaseTypeDefinitionRevision(String caseTypeId, long version) {
    GenericValue caseType = from('CaseType').where('caseTypeId', caseTypeId).queryOne()
    if (caseType == null) {
        return null
    }
    List<GenericValue> stages = from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).orderBy('stageSeq').queryList()
    List<GenericValue> steps = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).orderBy('stageSeq', 'sequenceNum', 'stepSeq').queryList()
    List<GenericValue> waits = from('CaseStepWait').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).orderBy('stageSeq', 'stepSeq').queryList()
    List<GenericValue> forms = from('CaseStepForm').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).orderBy('stageSeq', 'stepSeq').queryList()
    List<GenericValue> transitions = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).orderBy('stageSeq', 'stepSeq', 'outcomeEnumId', 'transitionSeq').queryList()
    List<GenericValue> fieldMappings = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).orderBy('stageSeq', 'stepSeq', 'sequenceNum', 'fieldSeq').queryList()
    List<GenericValue> fieldLabels = from('CaseFieldLabel').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).orderBy('stageSeq', 'stepSeq', 'fieldSeq', 'locale').queryList()
    List<GenericValue> serviceParams = from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).orderBy('stageSeq', 'stepSeq', 'paramSeq').queryList()
    List<GenericValue> serviceOutputs = from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).orderBy('stageSeq', 'stepSeq', 'outputSeq').queryList()
    GenericValue startForm = from('CaseTypeStartForm').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: version
    ])).queryOne()
    List<GenericValue> typeAttrs = from('CaseTypeAttr').where('caseTypeId', caseTypeId).orderBy('attrName').queryList()
    GenericValue accessPolicy = findVersionAccessPolicy(caseTypeId, version) ?: findAccessPolicy(caseType)
    List<GenericValue> accessRules = []
    if (accessPolicy != null) {
        accessRules = from('CaseAccessRule')
                .where('accessPolicyId', accessPolicy.accessPolicyId)
                .orderBy('ruleSeq')
                .queryList()
    }

    Map<String, Object> canonical = [
            caseType      : clean(caseType),
            stages        : stages.collect { GenericValue value -> clean(value) },
            steps         : steps.collect { GenericValue value -> clean(value) },
            waits         : waits.collect { GenericValue value -> clean(value) },
            forms         : forms.collect { GenericValue value -> clean(value) },
            transitions   : transitions.collect { GenericValue value -> clean(value) },
            fieldMappings : fieldMappings.collect { GenericValue value -> clean(value) },
            fieldLabels   : fieldLabels.collect { GenericValue value -> clean(value) },
            serviceParams : serviceParams.collect { GenericValue value -> clean(value) },
            serviceOutputs: serviceOutputs.collect { GenericValue value -> clean(value) },
            startForm     : startForm == null ? null : clean(startForm),
            accessPolicy  : accessPolicy == null ? null : clean(accessPolicy),
            accessRules   : accessRules.collect { GenericValue value -> clean(value) },
            typeAttrs     : typeAttrs.collect { GenericValue value -> clean(value) }
    ]
    return sha256(JsonOutput.toJson(canonical))
}

private Map<String, Object> clean(GenericValue value) {
    List<String> auditFields = ['createdStamp', 'createdTxStamp', 'lastUpdatedStamp', 'lastUpdatedTxStamp']
    Map<String, Object> fields = new LinkedHashMap<>()
    value.getAllFields().keySet().sort().each { String key ->
        if (!auditFields.contains(key)) {
            Object fieldValue = value.get(key)
            fields[key] = fieldValue instanceof Timestamp ? fieldValue.toInstant().toString() : fieldValue
        }
    }
    return fields
}

private String sha256(String text) {
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
    byte[] hash = digest.digest(text.getBytes('UTF-8'))
    return 'sha256:' + hash.collect { byte value -> String.format('%02x', value & 0xff) }.join()
}

private Long toLong(Object value) {
    if (value == null || value == '') {
        return null
    }
    return value as Long
}
