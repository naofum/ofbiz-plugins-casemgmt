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
package org.apache.ofbiz.casemgmt.cases

import java.sql.Timestamp

import org.apache.ofbiz.base.lang.JSON
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery

/**
 * Resolves declared service inputs and stores declared service outputs as
 * short-lived case variables.
 */
final class CaseServiceMappingHelper {

    private static final String DEFAULT_SCOPE = 'CASE_VAR_SCOPE_CASE'

    private CaseServiceMappingHelper() {
    }

    static Map buildServiceContext(Delegator delegator, GenericValue caseValue, GenericValue caseTask,
                                   GenericValue userLogin, Map<String, Object> baseContext) {
        Map<String, Object> context = new LinkedHashMap<>(baseContext)
        List<GenericValue> params = EntityQuery.use(delegator)
                .from('CaseStepServiceParam')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : caseValue.caseTypeId,
                        definitionVersion: caseValue.definitionVersion,
                        stageSeq         : caseTask.stageSeq,
                        stepSeq          : caseTask.stepSeq
                ]))
                .orderBy('paramSeq')
                .queryList()
        for (GenericValue param : params) {
            Object value = resolveValue(delegator, caseValue, userLogin, context, param)
            if (value == null || (value instanceof String && value.isEmpty())) {
                if (param.requiredIndicator == 'Y') {
                    return [error: "Required service input is not available: ${param.paramName}"]
                }
            } else {
                context[param.paramName] = value
            }
        }
        return [context: context]
    }

    static Map saveOutputs(Delegator delegator, GenericValue caseValue, GenericValue caseTask,
                           Map serviceResult, Timestamp now) {
        List<GenericValue> outputs = EntityQuery.use(delegator)
                .from('CaseStepServiceOutput')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : caseValue.caseTypeId,
                        definitionVersion: caseValue.definitionVersion,
                        stageSeq         : caseTask.stageSeq,
                        stepSeq          : caseTask.stepSeq
                ]))
                .orderBy('outputSeq')
                .queryList()
        int saved = 0
        for (GenericValue output : outputs) {
            Object value = serviceResult[output.outputName]
            if (value == null && output.requiredIndicator == 'Y') {
                return [error: "Required service output is missing: ${output.outputName}"]
            }
            if (value == null) {
                continue
            }
            saveVariable(delegator, caseValue.caseId, output, value, caseTask.caseTaskId, now)
            saved++
        }
        return [saved: saved]
    }

    static Object getVariableValue(Delegator delegator, String caseId, String variableName) {
        GenericValue variable = EntityQuery.use(delegator)
                .from('CaseVariable')
                .where(EntityCondition.makeCondition([caseId: caseId, variableName: variableName]))
                .queryOne()
        if (variable == null) {
            return null
        }
        switch (variable.valueTypeEnumId) {
            case 'CASE_VAR_NUMBER':
                return variable.valueNumber
            case 'CASE_VAR_DATE':
                return variable.valueDate
            case 'CASE_VAR_BOOLEAN':
                return variable.valueBoolean
            case 'CASE_VAR_JSON':
                return variable.jsonValue
            default:
                return variable.valueText
        }
    }

    static void deleteCaseVariables(Delegator delegator, String caseId) {
        EntityQuery.use(delegator)
                .from('CaseVariable')
                .where('caseId', caseId)
                .queryList()
                .each { GenericValue variable -> delegator.removeValue(variable) }
    }

    private static Object resolveValue(Delegator delegator, GenericValue caseValue, GenericValue userLogin,
                                       Map<String, Object> baseContext, GenericValue param) {
        String source = param.sourceValue
        switch (param.sourceTypeEnumId) {
            case 'CASE_SVC_SRC_LITERAL':
                return source
            case 'CASE_SVC_SRC_CONTEXT':
                return baseContext[source]
            case 'CASE_SVC_SRC_CASE':
                return caseValue.get(source)
            case 'CASE_SVC_SRC_VAR':
                return getVariableValue(delegator, caseValue.caseId, source)
            case 'CASE_SVC_SRC_ENTITY':
            case 'CASE_SVC_SRC_CCTX':
                return resolveContextField(delegator, caseValue.caseId, source)
            case 'CASE_SVC_SRC_FORM':
                return null
            default:
                return null
        }
    }

    private static Object resolveContextField(Delegator delegator, String caseId, String source) {
        if (!source || !source.contains('.')) {
            return null
        }
        int split = source.indexOf('.')
        String entityName = source.substring(0, split)
        String fieldName = source.substring(split + 1)
        List<GenericValue> contexts = EntityQuery.use(delegator)
                .from('CaseContext')
                .where(EntityCondition.makeCondition([caseId: caseId, entityName: entityName]))
                .orderBy('contextSeq')
                .queryList()
        for (GenericValue context : contexts) {
            Map<String, String> keys = CaseContextHelper.loadKeys(delegator, context)
            if (keys.isEmpty()) {
                continue
            }
            GenericValue target = CaseContextHelper.findTarget(delegator, entityName, keys)
            if (target != null) {
                return target.get(fieldName)
            }
        }
        return null
    }

    private static void saveVariable(Delegator delegator, String caseId, GenericValue mapping,
                                     Object value, String caseTaskId, Timestamp now) {
        String variableName = mapping.variableName
        GenericValue existing = EntityQuery.use(delegator)
                .from('CaseVariable')
                .where(EntityCondition.makeCondition([caseId: caseId, variableName: variableName]))
                .queryOne()
        String valueType = mapping.valueTypeEnumId ?: inferType(value)
        Map<String, Object> fields = [
                valueTypeEnumId : valueType,
                valueText       : null,
                valueNumber     : null,
                valueDate       : null,
                valueBoolean    : null,
                jsonValue       : null,
                sourceCaseTaskId: caseTaskId,
                scopeEnumId     : mapping.scopeEnumId ?: DEFAULT_SCOPE,
                sensitiveIndicator: mapping.sensitiveIndicator ?: 'N',
                expiresAt       : null,
                lastUpdatedDate : now
        ]
        switch (valueType) {
            case 'CASE_VAR_NUMBER':
                fields.valueNumber = value instanceof Number ? value : new BigDecimal(value.toString())
                break
            case 'CASE_VAR_DATE':
                fields.valueDate = value instanceof Timestamp ? value : new Timestamp((value as Date).time)
                break
            case 'CASE_VAR_BOOLEAN':
                fields.valueBoolean = value instanceof Boolean ? (value ? 'Y' : 'N') : value.toString()
                break
            case 'CASE_VAR_JSON':
                fields.jsonValue = value instanceof String ? value : JSON.from(value).toString()
                break
            default:
                fields.valueText = value.toString()
        }
        if (mapping.ttlSeconds && (mapping.ttlSeconds as long) > 0) {
            fields.expiresAt = new Timestamp(now.time + ((mapping.ttlSeconds as long) * 1000L))
        }
        if (existing == null) {
            fields.caseId = caseId
            fields.variableName = variableName
            fields.createdDate = now
            delegator.create('CaseVariable', fields)
        } else {
            existing.setFields(fields)
            existing.store()
        }
    }

    private static String inferType(Object value) {
        if (value instanceof Number) {
            return 'CASE_VAR_NUMBER'
        }
        if (value instanceof Date || value instanceof Timestamp) {
            return 'CASE_VAR_DATE'
        }
        if (value instanceof Boolean) {
            return 'CASE_VAR_BOOLEAN'
        }
        if (value instanceof Map || value instanceof Collection) {
            return 'CASE_VAR_JSON'
        }
        return 'CASE_VAR_STRING'
    }
}
