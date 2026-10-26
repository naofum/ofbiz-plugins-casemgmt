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

import org.apache.ofbiz.casemgmt.access.CaseAccessHelper
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

/**
 * CaseContext services for linking a case to business entities.
 *
 * Single-key links keep using CaseContext.pkValue for compatibility. All new
 * links also write CaseContextKey rows, which support composite primary keys.
 */
Map linkCaseContext() {
    String caseId = parameters.caseId
    String entityName = parameters.entityName?.trim()
    String pkValue = parameters.pkValue?.trim()
    String contextTypeEnumId = parameters.contextTypeEnumId?.trim() ?: 'CASE_CTX_RELATED'
    String roleTypeId = parameters.roleTypeId?.trim() ?: null
    String description = parameters.description
    Map businessKeys = parameters.businessKeys instanceof Map ? parameters.businessKeys as Map : null

    if (!caseId) {
        return errorMap('caseId is required')
    }
    if (!entityName) {
        return errorMap('entityName is required')
    }

    GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', caseId).queryOne()
    if (caseValue == null) {
        return errorMap("Case not found: $caseId")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_UPDATE')) {
        return errorMap(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_UPDATE'))
    }

    GenericValue contextType = EntityQuery.use(delegator).from('Enumeration').where(
            'enumTypeId', 'CASE_CONTEXT_TYPE', 'enumId', contextTypeEnumId).queryOne()
    if (contextType == null) {
        return errorMap("Invalid case context type: $contextTypeEnumId")
    }
    if (roleTypeId && EntityQuery.use(delegator).from('RoleType').where('roleTypeId', roleTypeId).queryOne() == null) {
        return errorMap("Role type not found: $roleTypeId")
    }

    Map normalized = CaseContextHelper.normalizeKeys(delegator, entityName, businessKeys, pkValue)
    if (normalized.error) {
        return errorMap(normalized.error)
    }
    Map<String, String> keys = normalized.keys as Map<String, String>
    GenericValue target = CaseContextHelper.findTarget(delegator, entityName, keys)
    if (target == null) {
        return errorMap("Target not found: $entityName / ${CaseContextHelper.describeKeys(keys)}")
    }

    List<GenericValue> existingContexts = EntityQuery.use(delegator)
            .from('CaseContext')
            .where('caseId', caseId)
            .queryList()
    GenericValue duplicate = existingContexts.find { GenericValue context ->
        context.entityName == entityName &&
                context.roleTypeId == roleTypeId &&
                CaseContextHelper.sameKeys(CaseContextHelper.loadKeys(delegator, context), keys)
    }
    if (duplicate != null) {
        return successMap([
                caseId    : caseId,
                contextSeq: duplicate.contextSeq,
                businessKeys: keys,
                linked    : false
        ])
    }

    Long contextSeq = delegator.getNextSeqId('CaseContext') as Long
    delegator.create('CaseContext', [
            caseId           : caseId,
            contextSeq       : contextSeq,
            entityName       : entityName,
            pkValue          : normalized.pkValue,
            contextTypeEnumId: contextTypeEnumId,
            roleTypeId       : roleTypeId,
            description      : description
    ])
    long keySeq = 1L
    keys.each { String keyName, String keyValue ->
        delegator.create('CaseContextKey', [
                caseId    : caseId,
                contextSeq: contextSeq,
                keySeq    : keySeq++,
                keyName   : keyName,
                keyValue  : keyValue
        ])
    }
    return successMap([
            caseId      : caseId,
            contextSeq  : contextSeq,
            businessKeys: keys,
            linked      : true
    ])
}

Map unlinkCaseContext() {
    String caseId = parameters.caseId
    Long contextSeq = parameters.contextSeq as Long

    if (!caseId) {
        return errorMap('caseId is required')
    }
    if (contextSeq == null) {
        return errorMap('contextSeq is required')
    }

    GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', caseId).queryOne()
    if (caseValue == null) {
        return errorMap("Case not found: $caseId")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_UPDATE')) {
        return errorMap(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_UPDATE'))
    }

    GenericValue context = EntityQuery.use(delegator).from('CaseContext')
            .where('caseId', caseId, 'contextSeq', contextSeq).queryOne()
    if (context == null) {
        return errorMap("Case context not found: $caseId / $contextSeq")
    }
    EntityQuery.use(delegator).from('CaseContextKey')
            .where('caseId', caseId, 'contextSeq', contextSeq)
            .queryList()
            .each { GenericValue key -> delegator.removeValue(key) }
    delegator.removeValue(context)
    return successMap([caseId: caseId, contextSeq: contextSeq, removed: true])
}

Map getCaseContext() {
    String caseId = parameters.caseId
    if (!caseId) {
        return errorMap('caseId is required')
    }

    GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', caseId).queryOne()
    if (caseValue == null) {
        return errorMap("Case not found: $caseId")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_VIEW')) {
        return errorMap(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_VIEW'))
    }

    List<Map<String, Object>> contexts = EntityQuery.use(delegator)
            .from('CaseContext')
            .where('caseId', caseId)
            .orderBy('contextSeq')
            .queryList()
            .collect { GenericValue context ->
                Map<String, String> businessKeys = CaseContextHelper.loadKeys(delegator, context)
                [
                        caseId           : context.caseId,
                        contextSeq       : context.contextSeq,
                        entityName       : context.entityName,
                        pkValue          : context.pkValue,
                        businessKeys     : businessKeys,
                        contextTypeEnumId: context.contextTypeEnumId,
                        roleTypeId       : context.roleTypeId,
                        description      : context.description
                ]
            }
    return successMap([contexts: contexts])
}

private Map successMap(Map values) {
    Map result = ServiceUtil.returnSuccess()
    result.putAll(values)
    return result
}

private Map errorMap(String message) {
    return ServiceUtil.returnError(message)
}
