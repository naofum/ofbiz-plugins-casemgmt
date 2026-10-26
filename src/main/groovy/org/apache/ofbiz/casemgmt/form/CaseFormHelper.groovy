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

import java.sql.Timestamp
import java.util.Locale

import org.apache.ofbiz.casemgmt.cases.CaseRuntimeHelper
import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.LocalDispatcher
import org.apache.ofbiz.service.ServiceUtil

/**
 * Metadata-driven case form rendering and saving.
 *
 * Visibility/read-only conditions are condition-service names, not arbitrary
 * expressions.
 */
final class CaseFormHelper {

    private CaseFormHelper() {
    }

    static Map getForm(Delegator delegator, LocalDispatcher dispatcher, GenericValue userLogin,
                       String caseId, Long stageSeq, Long stepSeq, Locale locale) {
        GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', caseId).queryOne()
        if (caseValue == null) {
            return [error: "Case not found: $caseId"]
        }
        long version = (caseValue.definitionVersion ?: 1) as long
        long effectiveStage = (stageSeq ?: caseValue.currentStageSeq ?: 1) as long
        long effectiveStep = (stepSeq ?: caseValue.currentStepSeq ?: 1) as long
        List<GenericValue> mappings = EntityQuery.use(delegator)
                .from('CaseFieldMapping')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : caseValue.caseTypeId,
                        definitionVersion: version,
                        stageSeq         : effectiveStage,
                        stepSeq          : effectiveStep
                ]))
                .orderBy('sequenceNum')
                .queryList()

        List<Map<String, Object>> fields = mappings.collect { GenericValue mapping ->
            Object value = readValue(delegator, caseValue, mapping)
            boolean visible = evaluateCondition(dispatcher, mapping.visibilityCondition, caseValue, mapping, value, userLogin)
            boolean readOnly = mapping.readOnlyCondition ?
                    evaluateCondition(dispatcher, mapping.readOnlyCondition, caseValue, mapping, value, userLogin) : false
            [
                    fieldName       : mapping.fieldName,
                    label           : resolveLabel(delegator, mapping, locale),
                    fieldTypeEnumId : mapping.fieldTypeEnumId,
                    requiredIndicator: mapping.requiredIndicator,
                    entityName      : mapping.entityName,
                    entityFieldName : mapping.entityFieldName,
                    value           : value,
                    visible         : visible,
                    readOnly        : readOnly,
                    options         : getOptions(delegator, mapping)
            ]
        }
        return [
                caseId  : caseId,
                stageSeq: effectiveStage,
                stepSeq : effectiveStep,
                fields  : fields
        ]
    }

    static Map saveForm(Delegator delegator, LocalDispatcher dispatcher, GenericValue userLogin,
                        String caseId, Long stageSeq, Long stepSeq, Map values, boolean validateOnly) {
        GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', caseId).queryOne()
        if (caseValue == null) {
            return [error: "Case not found: $caseId"]
        }
        long version = (caseValue.definitionVersion ?: 1) as long
        long effectiveStage = (stageSeq ?: caseValue.currentStageSeq ?: 1) as long
        long effectiveStep = (stepSeq ?: caseValue.currentStepSeq ?: 1) as long
        List<GenericValue> mappings = EntityQuery.use(delegator)
                .from('CaseFieldMapping')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : caseValue.caseTypeId,
                        definitionVersion: version,
                        stageSeq         : effectiveStage,
                        stepSeq          : effectiveStep
                ]))
                .queryList()
        List<String> errors = []
        int saved = 0
        String loginId = userLogin?.userLoginId ?: 'system'
        Timestamp now = new Timestamp(System.currentTimeMillis())

        for (GenericValue mapping : mappings) {
            Object raw = values["field_${mapping.fieldName}"] ?: values[mapping.fieldName]
            boolean visible = evaluateCondition(dispatcher, mapping.visibilityCondition, caseValue, mapping, raw, userLogin)
            if (!visible) {
                continue
            }
            if (mapping.requiredIndicator == 'Y' && (raw == null || raw.toString().trim().isEmpty())) {
                errors << "Field ${mapping.label ?: mapping.fieldName} is required"
                continue
            }
            if (raw == null || raw.toString().trim().isEmpty()) {
                continue
            }
            Object converted
            try {
                converted = convertValue(delegator, mapping, raw)
            } catch (Exception e) {
                errors << "Field ${mapping.fieldName}: ${e.message}"
                continue
            }
            if (validateOnly) {
                saved++
                continue
            }
            Object oldValue = readValue(delegator, caseValue, mapping)
            writeValue(delegator, caseValue, mapping, converted)
            CaseRuntimeHelper.createCaseEvent(delegator, caseId, 'CASE_FIELD_CHANGED', loginId, now, [
                    detail: "${mapping.fieldName}: ${oldValue} -> ${converted}"
            ])
            saved++
        }
        if (!errors.isEmpty()) {
            return [errors: errors]
        }
        return [saved: saved]
    }

    private static String resolveLabel(Delegator delegator, GenericValue mapping, Locale locale) {
        Locale effectiveLocale = locale ?: Locale.getDefault()
        String exactLocale = effectiveLocale.toLanguageTag().replace('-', '_')
        String localized = findLocalizedLabel(delegator, mapping, exactLocale)
        if (localized) {
            return localized
        }
        String language = effectiveLocale.language
        if (language && language != exactLocale) {
            localized = findLocalizedLabel(delegator, mapping, language)
            if (localized) {
                return localized
            }
        }
        return mapping.label ?: mapping.fieldName
    }

    private static String findLocalizedLabel(Delegator delegator, GenericValue mapping, String localeId) {
        if (!localeId) {
            return null
        }
        GenericValue localized = EntityQuery.use(delegator)
                .from('CaseFieldLabel')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : mapping.caseTypeId,
                        definitionVersion: mapping.definitionVersion,
                        stageSeq         : mapping.stageSeq,
                        stepSeq          : mapping.stepSeq,
                        fieldSeq         : mapping.fieldSeq,
                        locale           : localeId
                ]))
                .queryOne()
        return localized?.label?.toString()?.trim() ?: null
    }

    private static Object readValue(Delegator delegator, GenericValue caseValue, GenericValue mapping) {
        String entityName = mapping.entityName ?: 'Case'
        String fieldName = mapping.entityFieldName ?: mapping.fieldName
        if (entityName == 'Case') {
            return caseValue.get(fieldName)
        }
        GenericValue related = EntityQuery.use(delegator)
                .from(entityName)
                .where('caseId', caseValue.caseId)
                .queryOne()
        return related?.get(fieldName)
    }

    private static void writeValue(Delegator delegator, GenericValue caseValue,
                                   GenericValue mapping, Object value) {
        String entityName = mapping.entityName ?: 'Case'
        String fieldName = mapping.entityFieldName ?: mapping.fieldName
        if (entityName == 'Case') {
            caseValue.set(fieldName, value)
            caseValue.version = (caseValue.version ?: 0) + 1
            caseValue.store()
            return
        }
        GenericValue related = EntityQuery.use(delegator)
                .from(entityName)
                .where('caseId', caseValue.caseId)
                .queryOne()
        if (related == null) {
            related = delegator.create(entityName, [caseId: caseValue.caseId])
        }
        related.set(fieldName, value)
        related.store()
    }

    private static Object convertValue(Delegator delegator, GenericValue mapping, Object raw) {
        String text = raw.toString().trim()
        switch (mapping.fieldTypeEnumId) {
            case 'CASE_FIELD_NUMBER':
                return new BigDecimal(text)
            case 'CASE_FIELD_DATE':
                return java.sql.Date.valueOf(text)
            case 'CASE_FIELD_DATETIME':
                return Timestamp.valueOf(text)
            case 'CASE_FIELD_CHECKBOX':
                return ['Y', 'YES', 'TRUE', '1', 'ON'].contains(text.toUpperCase()) ? 'Y' : 'N'
            case 'CASE_FIELD_LOOKUP':
                if (mapping.lookupEntityName && mapping.lookupFieldName) {
                    GenericValue located = EntityQuery.use(delegator)
                            .from(mapping.lookupEntityName)
                            .where(mapping.lookupFieldName, text)
                            .queryOne()
                    if (located == null) {
                        throw new IllegalArgumentException("lookup value not found: $text")
                    }
                }
                return text
            default:
                return text
        }
    }

    private static List<Map<String, Object>> getOptions(Delegator delegator, GenericValue mapping) {
        if (!mapping.lookupEntityName || !mapping.lookupFieldName) {
            return []
        }
        try {
            return EntityQuery.use(delegator)
                    .from(mapping.lookupEntityName)
                    .queryList()
                    .collect { GenericValue value ->
                        String optionValue = value.get(mapping.lookupFieldName)
                        [value: optionValue, label: optionValue]
                    }
        } catch (Exception ignored) {
            return []
        }
    }

    private static boolean evaluateCondition(LocalDispatcher dispatcher, String serviceName,
                                             GenericValue caseValue, GenericValue mapping,
                                             Object value, GenericValue userLogin) {
        if (!serviceName) {
            return true
        }
        try {
            Map result = dispatcher.runSync(serviceName, [
                    caseId   : caseValue.caseId,
                    fieldName: mapping.fieldName,
                    value    : value,
                    userLogin: userLogin
            ])
            if (ServiceUtil.isError(result)) {
                return false
            }
            Object condition = result.conditionResult
            if (condition == null) {
                condition = result.success
            }
            if (condition instanceof Boolean) {
                return condition
            }
            return condition != null && Boolean.parseBoolean(condition.toString())
        } catch (Exception ignored) {
            return false
        }
    }
}
