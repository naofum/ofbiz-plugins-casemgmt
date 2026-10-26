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

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

/**
 * CRUD services for Service step input and output mappings.
 */
Map createCaseStepServiceParam() {
    Map check = checkDraftStep(parameters)
    if (check.error) {
        return errorMap(check.error)
    }
    String caseTypeId = parameters.caseTypeId
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = (parameters.stageSeq ?: 1) as long
    long stepSeq = (parameters.stepSeq ?: 1) as long
    String paramName = parameters.paramName?.trim()
    String sourceType = parameters.sourceTypeEnumId
    if (!paramName) {
        return errorMap('paramName is required')
    }
    if (!enumExists('CASE_SVC_INPUT_SRC', sourceType)) {
        return errorMap("Invalid service input source: $sourceType")
    }
    GenericValue existing = from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId       : caseTypeId,
            definitionVersion: version,
            stageSeq         : stageSeq,
            stepSeq          : stepSeq,
            paramName        : paramName
    ])).queryOne()
    if (existing != null) {
        return errorMap("Service input already exists: $paramName")
    }
    long paramSeq = (parameters.paramSeq ?: 0) as long
    if (paramSeq == 0) {
        List<GenericValue> current = from('CaseStepServiceParam').where(EntityCondition.makeCondition([
                caseTypeId       : caseTypeId,
                definitionVersion: version,
                stageSeq         : stageSeq,
                stepSeq          : stepSeq
        ])).queryList()
        paramSeq = current.isEmpty() ? 1L :
                ((current.collect { GenericValue value -> value.paramSeq as long }.max() ?: 0L) + 1L)
    }
    delegator.create('CaseStepServiceParam', [
            caseTypeId       : caseTypeId,
            definitionVersion: version,
            stageSeq         : stageSeq,
            stepSeq          : stepSeq,
            paramSeq         : paramSeq,
            paramName        : paramName,
            sourceTypeEnumId : sourceType,
            sourceValue      : parameters.sourceValue,
            requiredIndicator: parameters.requiredIndicator ?: 'N',
            description      : parameters.description
    ])
    return successMap([caseTypeId: caseTypeId, definitionVersion: version,
                       stageSeq: stageSeq, stepSeq: stepSeq, paramSeq: paramSeq])
}

Map deleteCaseStepServiceParam() {
    Map check = checkDraftStep(parameters)
    if (check.error) {
        return errorMap(check.error)
    }
    GenericValue param = from('CaseStepServiceParam').where(EntityCondition.makeCondition([
            caseTypeId       : parameters.caseTypeId,
            definitionVersion: (parameters.definitionVersion ?: 1) as long,
            stageSeq         : (parameters.stageSeq ?: 1) as long,
            stepSeq          : (parameters.stepSeq ?: 1) as long,
            paramSeq         : (parameters.paramSeq ?: 0) as long
    ])).queryOne()
    if (param == null) {
        return errorMap('Service input not found')
    }
    delegator.removeValue(param)
    return successMap([caseTypeId: parameters.caseTypeId,
                       definitionVersion: (parameters.definitionVersion ?: 1) as long,
                       stageSeq: (parameters.stageSeq ?: 1) as long,
                       stepSeq: (parameters.stepSeq ?: 1) as long])
}

Map createCaseStepServiceOutput() {
    Map check = checkDraftStep(parameters)
    if (check.error) {
        return errorMap(check.error)
    }
    String caseTypeId = parameters.caseTypeId
    long version = (parameters.definitionVersion ?: 1) as long
    long stageSeq = (parameters.stageSeq ?: 1) as long
    long stepSeq = (parameters.stepSeq ?: 1) as long
    String outputName = parameters.outputName?.trim()
    String variableName = parameters.variableName?.trim()
    if (!outputName) {
        return errorMap('outputName is required')
    }
    if (!variableName) {
        return errorMap('variableName is required')
    }
    GenericValue existing = from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
            caseTypeId       : caseTypeId,
            definitionVersion: version,
            stageSeq         : stageSeq,
            stepSeq          : stepSeq,
            outputName       : outputName
    ])).queryOne()
    if (existing != null) {
        return errorMap("Service output already exists: $outputName")
    }
    long outputSeq = (parameters.outputSeq ?: 0) as long
    if (outputSeq == 0) {
        List<GenericValue> current = from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
                caseTypeId       : caseTypeId,
                definitionVersion: version,
                stageSeq         : stageSeq,
                stepSeq          : stepSeq
        ])).queryList()
        outputSeq = current.isEmpty() ? 1L :
                ((current.collect { GenericValue value -> value.outputSeq as long }.max() ?: 0L) + 1L)
    }
    String valueType = parameters.valueTypeEnumId ?: 'CASE_VAR_STRING'
    String scope = parameters.scopeEnumId ?: 'CASE_VAR_SCOPE_CASE'
    if (!enumExists('CASE_VAR_TYPE', valueType)) {
        return errorMap("Invalid variable type: $valueType")
    }
    if (!enumExists('CASE_VAR_SCOPE', scope)) {
        return errorMap("Invalid variable scope: $scope")
    }
    delegator.create('CaseStepServiceOutput', [
            caseTypeId       : caseTypeId,
            definitionVersion: version,
            stageSeq         : stageSeq,
            stepSeq          : stepSeq,
            outputSeq        : outputSeq,
            outputName       : outputName,
            variableName     : variableName,
            valueTypeEnumId  : valueType,
            requiredIndicator: parameters.requiredIndicator ?: 'N',
            sensitiveIndicator: parameters.sensitiveIndicator ?: 'N',
            scopeEnumId      : scope,
            ttlSeconds       : parameters.ttlSeconds,
            description      : parameters.description
    ])
    return successMap([caseTypeId: caseTypeId, definitionVersion: version,
                       stageSeq: stageSeq, stepSeq: stepSeq, outputSeq: outputSeq])
}

Map deleteCaseStepServiceOutput() {
    Map check = checkDraftStep(parameters)
    if (check.error) {
        return errorMap(check.error)
    }
    GenericValue output = from('CaseStepServiceOutput').where(EntityCondition.makeCondition([
            caseTypeId       : parameters.caseTypeId,
            definitionVersion: (parameters.definitionVersion ?: 1) as long,
            stageSeq         : (parameters.stageSeq ?: 1) as long,
            stepSeq          : (parameters.stepSeq ?: 1) as long,
            outputSeq        : (parameters.outputSeq ?: 0) as long
    ])).queryOne()
    if (output == null) {
        return errorMap('Service output not found')
    }
    delegator.removeValue(output)
    return successMap([caseTypeId: parameters.caseTypeId,
                       definitionVersion: (parameters.definitionVersion ?: 1) as long,
                       stageSeq: (parameters.stageSeq ?: 1) as long,
                       stepSeq: (parameters.stepSeq ?: 1) as long])
}

private Map checkDraftStep(Map parameters) {
    GenericValue caseType = from('CaseType').where('caseTypeId', parameters.caseTypeId).queryOne()
    if (caseType == null) {
        return [error: "Case type not found: ${parameters.caseTypeId}"]
    }
    if (caseType.statusId != 'CASE_TYPE_DRAFT') {
        return [error: "Case type is not a draft: ${parameters.caseTypeId}"]
    }
    long version = (parameters.definitionVersion ?: caseType.definitionVersion ?: 1) as long
    if (version != ((caseType.definitionVersion ?: 1) as long)) {
        return [error: "Definition version mismatch: $version"]
    }
    GenericValue step = from('CaseStepDef').where(EntityCondition.makeCondition([
            caseTypeId       : parameters.caseTypeId,
            definitionVersion: version,
            stageSeq         : (parameters.stageSeq ?: 1) as long,
            stepSeq          : (parameters.stepSeq ?: 1) as long
    ])).queryOne()
    if (step == null) {
        return [error: 'Case step not found']
    }
    return [ok: true]
}

private boolean enumExists(String enumTypeId, String enumId) {
    if (!enumId) {
        return false
    }
    return from('Enumeration').where(EntityCondition.makeCondition([
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
