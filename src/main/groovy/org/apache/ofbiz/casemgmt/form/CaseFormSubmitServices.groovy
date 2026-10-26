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

import org.apache.ofbiz.casemgmt.access.CaseAccessHelper
import org.apache.ofbiz.casemgmt.cases.CaseConcurrencyHelper
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.transaction.TransactionUtil
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

/**
 * Starts a case from a start form and completes a task from a task form.
 */
Map submitStartCase() {
    String caseTypeId = parameters.caseTypeId
    GenericValue caseType = EntityQuery.use(delegator).from('CaseType')
            .where('caseTypeId', caseTypeId).queryOne()
    if (caseType == null) {
        return errorMap("Case type not found: $caseTypeId")
    }
    if (caseType.statusId != 'CASE_TYPE_ACTIVE') {
        return errorMap("Case type is not active: $caseTypeId")
    }

    long version = CaseFormMetadataHelper.definitionVersion(caseType)
    Map<String, Object> startForm = CaseFormMetadataHelper.loadStartForm(delegator, caseTypeId, version)
    Map<String, Object> formValues = valuesMap(parameters.formValues)
    Map<String, Object> startResult = [:]
    if (startForm.startServiceName) {
        Map<String, Object> serviceContext = new LinkedHashMap<>(formValues)
        serviceContext.userLogin = userLogin
        serviceContext.caseTypeId = caseTypeId
        try {
            startResult = dispatcher.runSync(startForm.startServiceName as String, serviceContext)
        } catch (Exception e) {
            rollback("Start service failed: ${startForm.startServiceName}: ${e.message}")
            return errorMap("Start service failed: ${startForm.startServiceName}: ${e.message}")
        }
        if (ServiceUtil.isError(startResult)) {
            rollback(ServiceUtil.getErrorMessage(startResult))
            return startResult
        }
    }

    Map<String, Object> createContext = [
            userLogin      : userLogin,
            caseTypeId     : caseTypeId,
            customerPartyId: startResult.customerPartyId ?: parameters.customerPartyId,
            ownerPartyId   : startResult.ownerPartyId ?: parameters.ownerPartyId,
            priority       : startResult.priority ?: parameters.priority,
            channelEnumId  : startResult.channelEnumId ?: parameters.channelEnumId,
            description    : startResult.description ?: parameters.description
    ]
    Map caseResult = dispatcher.runSync('createCase', createContext)
    if (ServiceUtil.isError(caseResult)) {
        rollback(ServiceUtil.getErrorMessage(caseResult))
        return caseResult
    }
    String caseId = caseResult.caseId
    Map linkResult = linkStartContext(caseId, startResult)
    if (linkResult.error) {
        rollback(linkResult.error)
        return errorMap(linkResult.error)
    }
    return successMap([
            caseId          : caseId,
            caseNumber      : caseResult.caseNumber,
            definitionVersion: version,
            linkedContexts  : linkResult.linked ?: 0
    ])
}

Map submitCaseTaskForm() {
    String caseTaskId = parameters.caseTaskId
    GenericValue task = EntityQuery.use(delegator).from('CaseTask')
            .where('caseTaskId', caseTaskId).queryOne()
    if (task == null) {
        return errorMap("Case task not found: $caseTaskId")
    }
    if (['TASK_COMPLETED', 'TASK_CANCELLED', 'TASK_SKIPPED'].contains(task.statusId)) {
        return errorMap("Case task is already closed: $caseTaskId")
    }
    GenericValue caseValue = EntityQuery.use(delegator).from('Case')
            .where('caseId', task.caseId).queryOne()
    if (caseValue == null) {
        return errorMap("Case not found: ${task.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_UPDATE')) {
        return errorMap(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_UPDATE'))
    }
    if (!CaseConcurrencyHelper.lockCase(delegator, task.caseId)) {
        return errorMap('Case was updated by another operation; reload and retry')
    }
    GenericValue lockedCase = EntityQuery.use(delegator).from('Case')
            .where('caseId', task.caseId).queryOne()
    GenericValue lockedTask = EntityQuery.use(delegator).from('CaseTask')
            .where('caseTaskId', caseTaskId).queryOne()
    if (lockedCase == null || lockedTask == null ||
            ['TASK_COMPLETED', 'TASK_CANCELLED', 'TASK_SKIPPED'].contains(lockedTask.statusId) ||
            (lockedTask.stageSeq as long) != (lockedCase.currentStageSeq as long) ||
            (lockedTask.stageVisitSeq as long) != (lockedCase.currentStageVisitSeq as long)) {
        return errorMap("Case task is no longer available: $caseTaskId")
    }

    long version = (lockedCase.definitionVersion ?: 1) as long
    Map<String, Object> stepForm = CaseFormMetadataHelper.loadStepForm(
            delegator, lockedCase.caseTypeId, version,
            lockedTask.stageSeq as long, lockedTask.stepSeq as long)
    Map<String, Object> formValues = valuesMap(parameters.formValues)
    Map<String, Object> serviceResult = [:]
    if (stepForm.completeServiceName) {
        Map<String, Object> serviceContext = new LinkedHashMap<>(formValues)
        serviceContext.caseId = lockedCase.caseId
        serviceContext.caseTaskId = lockedTask.caseTaskId
        serviceContext.stageSeq = lockedTask.stageSeq
        serviceContext.stepSeq = lockedTask.stepSeq
        serviceContext.userLogin = userLogin
        try {
            serviceResult = dispatcher.runSync(stepForm.completeServiceName as String, serviceContext)
        } catch (Exception e) {
            rollback("Task form service failed: ${stepForm.completeServiceName}: ${e.message}")
            return errorMap("Task form service failed: ${stepForm.completeServiceName}: ${e.message}")
        }
        if (ServiceUtil.isError(serviceResult)) {
            rollback(ServiceUtil.getErrorMessage(serviceResult))
            return serviceResult
        }
    } else if (!stepForm.formName) {
        Map<String, Object> fieldValues = formValues.findAll { String key, Object value ->
            key.startsWith('field_')
        }
        if (!fieldValues.isEmpty()) {
            Map saveResult = dispatcher.runSync('saveCaseForm', [
                    userLogin: userLogin,
                    caseId   : lockedCase.caseId,
                    stageSeq : lockedTask.stageSeq,
                    stepSeq  : lockedTask.stepSeq,
                    values   : fieldValues
            ])
            if (ServiceUtil.isError(saveResult)) {
                rollback(ServiceUtil.getErrorMessage(saveResult))
                return saveResult
            }
        }
    }

    String outcomeEnumId = serviceResult.outcomeEnumId ?: parameters.outcomeEnumId ?: formValues.outcomeEnumId
    Map completeContext = [
            userLogin    : userLogin,
            caseTaskId   : caseTaskId,
            caseId       : lockedCase.caseId,
            outcomeEnumId: outcomeEnumId ?: 'CASE_OUT_COMPLETE',
            notes        : parameters.notes ?: formValues.notes
    ]
    Map completeResult = dispatcher.runSync('completeCaseTask', completeContext)
    if (ServiceUtil.isError(completeResult)) {
        rollback(ServiceUtil.getErrorMessage(completeResult))
        return completeResult
    }
    Map result = successMap(new LinkedHashMap<>(completeResult))
    result.formServiceName = stepForm.completeServiceName
    return result
}

private Map linkStartContext(String caseId, Map startResult) {
    List<Map<String, Object>> links = []
    if (startResult.contextLinks instanceof Collection) {
        startResult.contextLinks.each { Object link ->
            if (link instanceof Map) {
                links << new LinkedHashMap<>(link as Map)
            }
        }
    }
    if (links.isEmpty() && startResult.businessEntityName) {
        links << [
                entityName       : startResult.businessEntityName,
                businessKeys     : startResult.businessKeys,
                contextTypeEnumId: startResult.contextTypeEnumId ?: 'CASE_CTX_PRIMARY',
                roleTypeId       : startResult.roleTypeId,
                description      : startResult.contextDescription
        ]
    }
    int linked = 0
    for (Map<String, Object> link : links) {
        String entityName = (link.entityName ?: startResult.businessEntityName)?.toString()
        Object keySource = link.businessKeys ?: startResult.businessKeys
        Map businessKeys = keySource instanceof Map ? new LinkedHashMap<>(keySource as Map) : null
        String pkValue = link.pkValue?.toString()
        if (!pkValue && keySource != null && !(keySource instanceof Map)) {
            pkValue = keySource.toString()
        }
        if (!entityName || (!pkValue && !businessKeys)) {
            return [error: 'Start service returned an incomplete CaseContext link']
        }
        Map<String, Object> linkContext = [
                userLogin       : userLogin,
                caseId          : caseId,
                entityName      : entityName,
                contextTypeEnumId: link.contextTypeEnumId ?: startResult.contextTypeEnumId ?: 'CASE_CTX_PRIMARY',
                roleTypeId      : link.roleTypeId ?: startResult.roleTypeId,
                description     : link.description ?: startResult.contextDescription
        ]
        if (businessKeys) {
            linkContext.businessKeys = businessKeys
        }
        if (pkValue) {
            linkContext.pkValue = pkValue
        }
        Map linkResult = dispatcher.runSync('linkCaseContext', linkContext)
        if (ServiceUtil.isError(linkResult)) {
            return [error: ServiceUtil.getErrorMessage(linkResult)]
        }
        linked++
    }
    return [linked: linked]
}

private Map<String, Object> valuesMap(Object values) {
    return values instanceof Map ? new LinkedHashMap<>(values as Map) : [:]
}

private void rollback(String message) {
    TransactionUtil.setRollbackOnly(message, null)
}

private Map successMap(Map values) {
    Map result = ServiceUtil.returnSuccess()
    result.putAll(values)
    return result
}

private Map errorMap(String message) {
    return ServiceUtil.returnError(message)
}
