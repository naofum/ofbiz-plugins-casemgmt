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

import java.util.Locale

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

/**
 * Locale-specific labels for metadata-driven case fields.
 */
Map saveCaseFieldLabel() {
    Map check = checkDraft(parameters.caseTypeId, parameters.definitionVersion)
    if (check.error) {
        return errorMap(check.error)
    }
    long stageSeq = (parameters.stageSeq ?: 1) as long
    long stepSeq = (parameters.stepSeq ?: 1) as long
    long fieldSeq = (parameters.fieldSeq ?: 0) as long
    GenericValue mapping = EntityQuery.use(delegator).from('CaseFieldMapping')
            .where(EntityCondition.makeCondition([
                    caseTypeId       : check.caseTypeId,
                    definitionVersion: check.definitionVersion,
                    stageSeq         : stageSeq,
                    stepSeq          : stepSeq,
                    fieldSeq         : fieldSeq
            ])).queryOne()
    if (mapping == null) {
        return errorMap("Case field not found: ${check.caseTypeId}/${stageSeq}/${stepSeq}/${fieldSeq}")
    }
    String localeId = normalizeLocale(parameters.labelLocale)
    if (!localeId) {
        return errorMap('Locale is required')
    }
    String label = parameters.label?.toString()?.trim()
    if (!label) {
        return errorMap('Label is required')
    }
    Map<String, Object> fields = [
            label      : label,
            description: parameters.description
    ]
    GenericValue existing = EntityQuery.use(delegator).from('CaseFieldLabel')
            .where(EntityCondition.makeCondition([
                    caseTypeId       : check.caseTypeId,
                    definitionVersion: check.definitionVersion,
                    stageSeq         : stageSeq,
                    stepSeq          : stepSeq,
                    fieldSeq         : fieldSeq,
                    locale           : localeId
            ])).queryOne()
    if (existing == null) {
        fields.caseTypeId = check.caseTypeId
        fields.definitionVersion = check.definitionVersion
        fields.stageSeq = stageSeq
        fields.stepSeq = stepSeq
        fields.fieldSeq = fieldSeq
        fields.locale = localeId
        delegator.create('CaseFieldLabel', fields)
    } else {
        existing.setFields(fields)
        existing.store()
    }
    return successMap([
            caseTypeId       : check.caseTypeId,
            definitionVersion: check.definitionVersion,
            stageSeq         : stageSeq,
            stepSeq          : stepSeq,
            fieldSeq         : fieldSeq,
            labelLocale      : localeId
    ])
}

Map deleteCaseFieldLabel() {
    Map check = checkDraft(parameters.caseTypeId, parameters.definitionVersion)
    if (check.error) {
        return errorMap(check.error)
    }
    long stageSeq = (parameters.stageSeq ?: 1) as long
    long stepSeq = (parameters.stepSeq ?: 1) as long
    long fieldSeq = (parameters.fieldSeq ?: 0) as long
    String localeId = normalizeLocale(parameters.labelLocale)
    if (!localeId) {
        return errorMap('Locale is required')
    }
    GenericValue label = EntityQuery.use(delegator).from('CaseFieldLabel')
            .where(EntityCondition.makeCondition([
                    caseTypeId       : check.caseTypeId,
                    definitionVersion: check.definitionVersion,
                    stageSeq         : stageSeq,
                    stepSeq          : stepSeq,
                    fieldSeq         : fieldSeq,
                    locale           : localeId
            ])).queryOne()
    if (label != null) {
        delegator.removeValue(label)
    }
    return successMap([
            caseTypeId       : check.caseTypeId,
            definitionVersion: check.definitionVersion,
            stageSeq         : stageSeq,
            stepSeq          : stepSeq,
            fieldSeq         : fieldSeq,
            labelLocale      : localeId
    ])
}

private String normalizeLocale(Object value) {
    String raw = value?.toString()?.trim()
    if (!raw) {
        return null
    }
    try {
        Locale parsed = Locale.forLanguageTag(raw.replace('_', '-'))
        if (parsed.language && parsed.language != 'und') {
            return parsed.toLanguageTag().replace('-', '_')
        }
    } catch (Exception ignored) {
        // Fall back to the entered value.
    }
    return raw.replace('-', '_')
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
