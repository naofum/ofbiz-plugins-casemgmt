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

import java.sql.Timestamp

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

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
    GenericValue caseType = from('CaseType').where('caseTypeId', parameters.caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    if (caseType.statusId != 'CASE_TYPE_DRAFT') {
        return errorMap("Case type is not a draft: ${parameters.caseTypeId}")
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
    GenericValue caseType = from('CaseType').where('caseTypeId', parameters.caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    long activeVersion = (caseType.activeDefinitionVersion ?: caseType.definitionVersion ?: 1) as long
    long draftVersion = (caseType.definitionVersion ?: 1) as long
    if (draftVersion > activeVersion) {
        return errorMap("A draft already exists: ${caseType.caseTypeId} version $draftVersion")
    }
    long newVersion = activeVersion + 1
    cloneDefinition(caseType.caseTypeId, activeVersion, newVersion)
    caseType.definitionVersion = newVersion
    caseType.statusId = 'CASE_TYPE_DRAFT'
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
    GenericValue caseType = from('CaseType').where('caseTypeId', parameters.caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    long version = (caseType.definitionVersion ?: 1) as long
    List<String> errors = validateDefinition(caseType, version)
    if (!errors.isEmpty()) {
        return errorMap("Case type definition is invalid: ${errors.join('; ')}")
    }
    caseType.activeDefinitionVersion = version
    caseType.statusId = 'CASE_TYPE_ACTIVE'
    caseType.lastModifiedDate = UtilDateTime.nowTimestamp()
    caseType.store()
    return successMap([caseTypeId: caseType.caseTypeId, definitionVersion: version])
}

Map retireCaseType() {
    GenericValue caseType = from('CaseType').where('caseTypeId', parameters.caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    caseType.statusId = 'CASE_TYPE_RETIRED'
    caseType.lastModifiedDate = UtilDateTime.nowTimestamp()
    caseType.store()
    return successMap([caseTypeId: caseType.caseTypeId])
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
    List<Map<String, Object>> stageChanges = diffStages(caseType.caseTypeId, fromVersion, toVersion)
    List<Map<String, Object>> stepChanges = diffSteps(caseType.caseTypeId, fromVersion, toVersion)
    List<Map<String, Object>> transitionChanges = diffTransitions(caseType.caseTypeId, fromVersion, toVersion)
    List<Map<String, Object>> fieldChanges = diffFields(caseType.caseTypeId, fromVersion, toVersion)
    List<Map<String, Object>> serviceParamChanges = diffServiceParams(caseType.caseTypeId, fromVersion, toVersion)
    List<Map<String, Object>> serviceOutputChanges = diffServiceOutputs(caseType.caseTypeId, fromVersion, toVersion)
    List<Map<String, Object>> startFormChanges = diffStartForm(caseType.caseTypeId, fromVersion, toVersion)
    List<Map<String, Object>> stepFormChanges = diffStepForms(caseType.caseTypeId, fromVersion, toVersion)
    List<Map<String, Object>> waitChanges = diffWaits(caseType.caseTypeId, fromVersion, toVersion)
    int total = stageChanges.size() + stepChanges.size() + transitionChanges.size() +
            fieldChanges.size() + serviceParamChanges.size() + serviceOutputChanges.size() +
            startFormChanges.size() + stepFormChanges.size() + waitChanges.size()
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
            waitChanges        : waitChanges
    ])
}

private List<Map<String, Object>> diffStages(String caseTypeId, long fromVersion, long toVersion) {
    Map<String, GenericValue> before = from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value -> [(value.stageSeq.toString()): value] }
    Map<String, GenericValue> after = from('CaseStageDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value -> [(value.stageSeq.toString()): value] }
    return diffMaps(before, after, { GenericValue value ->
        "Stage ${value.stageSeq}: ${value.stageName} / autoAdvance=${value.autoAdvance} / entry=${value.entryServiceName ?: ''} / exit=${value.exitServiceName ?: ''}"
    }, 'stageSeq')
}

private List<Map<String, Object>> diffSteps(String caseTypeId, long fromVersion, long toVersion) {
    Map<String, GenericValue> before = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value -> [("${value.stageSeq}/${value.stepSeq}".toString()): value] }
    Map<String, GenericValue> after = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value -> [("${value.stageSeq}/${value.stepSeq}".toString()): value] }
    return diffMaps(before, after, { GenericValue value ->
        "${value.stepName} / ${value.stepTypeEnumId} / service=${value.serviceName ?: ''} / form=${value.formId ?: ''} / subCaseType=${value.subCaseTypeId ?: ''} / policy=${value.assignmentPolicyId ?: ''} / sla=${value.slaId ?: ''} / required=${value.requiredIndicator}"
    }, 'step')
}

private List<Map<String, Object>> diffTransitions(String caseTypeId, long fromVersion, long toVersion) {
    Map<String, GenericValue> before = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.outcomeEnumId}/${value.transitionSeq}".toString()): value]
    }
    Map<String, GenericValue> after = from('CaseStepTransition').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.outcomeEnumId}/${value.transitionSeq}".toString()): value]
    }
    return diffMaps(before, after, { GenericValue value ->
        "to=${value.toStageSeq ?: ''}/${value.toStepSeq ?: ''} / priority=${value.priority ?: 0} / default=${value.isDefault} / condition=${value.conditionServiceName ?: ''}"
    }, 'transition')
}

private List<Map<String, Object>> diffFields(String caseTypeId, long fromVersion, long toVersion) {
    Map<String, GenericValue> before = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.fieldSeq}".toString()): value]
    }
    Map<String, GenericValue> after = from('CaseFieldMapping').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.fieldSeq}".toString()): value]
    }
    return diffMaps(before, after, { GenericValue value ->
        "${value.fieldName} -> ${value.entityName}.${value.entityFieldName} / ${value.label} / ${value.fieldTypeEnumId} / required=${value.requiredIndicator}"
    }, 'field')
}

private List<Map<String, Object>> diffServiceParams(String caseTypeId, long fromVersion, long toVersion) {
    Map<String, GenericValue> before = from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.paramSeq}".toString()): value]
    }
    Map<String, GenericValue> after = from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.paramSeq}".toString()): value]
    }
    return diffMaps(before, after, { GenericValue value ->
        "${value.paramName} <- ${value.sourceTypeEnumId}:${value.sourceValue ?: ''} / required=${value.requiredIndicator}"
    }, 'serviceInput')
}

private List<Map<String, Object>> diffServiceOutputs(String caseTypeId, long fromVersion, long toVersion) {
    Map<String, GenericValue> before = from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}/${value.outputSeq}".toString()): value]
    }
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

private List<Map<String, Object>> diffStepForms(String caseTypeId, long fromVersion, long toVersion) {
    Map<String, GenericValue> before = from('CaseStepForm').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}".toString()): value]
    }
    Map<String, GenericValue> after = from('CaseStepForm').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}".toString()): value]
    }
    return diffMaps(before, after, { GenericValue value ->
        "${value.formLocation}#${value.formName} / target=${value.formTarget} / service=${value.completeServiceName ?: ''}"
    }, 'stepForm')
}

private List<Map<String, Object>> diffWaits(String caseTypeId, long fromVersion, long toVersion) {
    Map<String, GenericValue> before = from('CaseStepWait').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: fromVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}".toString()): value]
    }
    Map<String, GenericValue> after = from('CaseStepWait').where(EntityCondition.makeCondition([
            caseTypeId: caseTypeId, definitionVersion: toVersion
    ])).queryList().collectEntries { GenericValue value ->
        [("${value.stageSeq}/${value.stepSeq}".toString()): value]
    }
    return diffMaps(before, after, { GenericValue value ->
        "signal=${value.signalName ?: ''} / timeout=${value.timeoutDuration ?: ''} min / outcome=${value.timeoutOutcomeEnumId ?: ''} / ${value.description ?: ''}"
    }, 'waitSetting')
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
    GenericValue caseType = from('CaseType').where('caseTypeId', parameters.caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    if (caseType.statusId != 'CASE_TYPE_DRAFT') {
        return errorMap("Only a draft case type can be deleted: ${parameters.caseTypeId}")
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
    if (caseType.accessPolicyId) {
        from('CaseAccessRule').where('accessPolicyId', caseType.accessPolicyId).queryList()
                .each { GenericValue value -> delegator.removeValue(value) }
        GenericValue policy = from('CaseAccessPolicy').where('accessPolicyId', caseType.accessPolicyId).queryOne()
        if (policy != null) {
            delegator.removeValue(policy)
        }
    }
    delegator.removeValue(caseType)
    return successMap([caseTypeId: parameters.caseTypeId])
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
    if (caseType.statusId != 'CASE_TYPE_DRAFT') {
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
    if (caseType.accessPolicyId &&
            from('CaseAccessPolicy').where('accessPolicyId', caseType.accessPolicyId).queryOne() == null) {
        errors << "Access policy not found: ${caseType.accessPolicyId}"
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
