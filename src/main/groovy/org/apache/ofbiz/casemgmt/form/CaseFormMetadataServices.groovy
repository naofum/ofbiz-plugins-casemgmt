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
package org.apache.ofbiz.casemgmt.form

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

/**
 * CRUD and lookup services for case start/task form metadata.
 */
Map getCaseTypeStartForm() {
    GenericValue caseType = EntityQuery.use(delegator).from('CaseType')
            .where('caseTypeId', parameters.caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    long version = (parameters.definitionVersion ?: CaseFormMetadataHelper.definitionVersion(caseType)) as long
    return successMap([
            caseTypeId       : caseType.caseTypeId,
            definitionVersion: version,
            startForm        : CaseFormMetadataHelper.loadStartForm(delegator, caseType.caseTypeId, version)
    ])
}

Map getCaseStepForm() {
    GenericValue caseType = EntityQuery.use(delegator).from('CaseType')
            .where('caseTypeId', parameters.caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: ${parameters.caseTypeId}")
    }
    long version = (parameters.definitionVersion ?: CaseFormMetadataHelper.definitionVersion(caseType)) as long
    long stageSeq = (parameters.stageSeq ?: 1) as long
    long stepSeq = (parameters.stepSeq ?: 1) as long
    return successMap([
            caseTypeId       : caseType.caseTypeId,
            definitionVersion: version,
            stageSeq         : stageSeq,
            stepSeq          : stepSeq,
            stepForm         : CaseFormMetadataHelper.loadStepForm(
                    delegator, caseType.caseTypeId, version, stageSeq, stepSeq)
    ])
}

Map saveCaseTypeStartForm() {
    Map check = checkDraft(parameters.caseTypeId, parameters.definitionVersion)
    if (check.error) {
        return errorMap(check.error)
    }
    String target = parameters.formTarget?.trim() ?: CaseFormMetadataHelper.START_FORM_TARGET
    String formError = CaseFormMetadataHelper.validateFormDefinition(
            delegator, dispatcher, parameters.formLocation, parameters.formName, target)
    if (formError) {
        return errorMap(formError)
    }
    Map<String, Object> fields = [
            formLocation    : parameters.formLocation?.trim(),
            formName        : parameters.formName?.trim(),
            formTarget      : target,
            startServiceName: parameters.startServiceName?.trim(),
            submitLabel     : parameters.submitLabel?.trim(),
            description     : parameters.description
    ]
    GenericValue existing = EntityQuery.use(delegator).from('CaseTypeStartForm')
            .where(EntityCondition.makeCondition([
                    caseTypeId       : check.caseTypeId,
                    definitionVersion: check.definitionVersion
            ])).queryOne()
    if (existing == null) {
        fields.caseTypeId = check.caseTypeId
        fields.definitionVersion = check.definitionVersion
        delegator.create('CaseTypeStartForm', fields)
    } else {
        existing.setFields(fields)
        existing.store()
    }
    return successMap([caseTypeId: check.caseTypeId, definitionVersion: check.definitionVersion])
}

Map deleteCaseTypeStartForm() {
    Map check = checkDraft(parameters.caseTypeId, parameters.definitionVersion)
    if (check.error) {
        return errorMap(check.error)
    }
    GenericValue form = EntityQuery.use(delegator).from('CaseTypeStartForm')
            .where(EntityCondition.makeCondition([
                    caseTypeId       : check.caseTypeId,
                    definitionVersion: check.definitionVersion
            ])).queryOne()
    if (form != null) {
        delegator.removeValue(form)
    }
    return successMap([caseTypeId: check.caseTypeId, definitionVersion: check.definitionVersion])
}

Map saveCaseStepForm() {
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
    String target = parameters.formTarget?.trim() ?: CaseFormMetadataHelper.TASK_FORM_TARGET
    String formError = CaseFormMetadataHelper.validateFormDefinition(
            delegator, dispatcher, parameters.formLocation, parameters.formName, target)
    if (formError) {
        return errorMap(formError)
    }
    Map<String, Object> fields = [
            formLocation       : parameters.formLocation?.trim(),
            formName           : parameters.formName?.trim(),
            formTarget         : target,
            completeServiceName: parameters.completeServiceName?.trim(),
            description        : parameters.description
    ]
    GenericValue existing = EntityQuery.use(delegator).from('CaseStepForm')
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
        delegator.create('CaseStepForm', fields)
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

Map deleteCaseStepForm() {
    Map check = checkDraft(parameters.caseTypeId, parameters.definitionVersion)
    if (check.error) {
        return errorMap(check.error)
    }
    long stageSeq = (parameters.stageSeq ?: 1) as long
    long stepSeq = (parameters.stepSeq ?: 1) as long
    GenericValue form = EntityQuery.use(delegator).from('CaseStepForm')
            .where(EntityCondition.makeCondition([
                    caseTypeId       : check.caseTypeId,
                    definitionVersion: check.definitionVersion,
                    stageSeq         : stageSeq,
                    stepSeq          : stepSeq
            ])).queryOne()
    if (form != null) {
        delegator.removeValue(form)
    }
    return successMap([
            caseTypeId       : check.caseTypeId,
            definitionVersion: check.definitionVersion,
            stageSeq         : stageSeq,
            stepSeq          : stepSeq
    ])
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

private Map successMap(Map values) {
    Map result = ServiceUtil.returnSuccess()
    result.putAll(values)
    return result
}

private Map errorMap(String message) {
    return ServiceUtil.returnError(message)
}
