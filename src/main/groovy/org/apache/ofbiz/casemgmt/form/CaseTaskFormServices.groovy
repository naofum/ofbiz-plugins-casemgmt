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
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

/**
 * Resolves the task form for a case task, preferring CaseStepForm over the
 * legacy CaseFieldMapping dynamic form.
 */
Map getCaseTaskForm() {
    GenericValue task = EntityQuery.use(delegator).from('CaseTask')
            .where('caseTaskId', parameters.caseTaskId).queryOne()
    if (task == null) {
        return errorMap("Case task not found: ${parameters.caseTaskId}")
    }
    GenericValue caseValue = EntityQuery.use(delegator).from('Case')
            .where('caseId', task.caseId).queryOne()
    if (caseValue == null) {
        return errorMap("Case not found: ${task.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_VIEW')) {
        return errorMap(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_VIEW'))
    }

    long version = (caseValue.definitionVersion ?: 1) as long
    long stageSeq = (task.stageSeq ?: caseValue.currentStageSeq ?: 1) as long
    long stepSeq = (task.stepSeq ?: caseValue.currentStepSeq ?: 1) as long
    Map<String, Object> stepForm = CaseFormMetadataHelper.loadStepForm(
            delegator, caseValue.caseTypeId, version, stageSeq, stepSeq)
    String formMode = 'SIMPLE'
    List<Map<String, Object>> fields = []
    if (stepForm.formName) {
        formMode = 'STEP_FORM'
    } else {
        Map form = CaseFormHelper.getForm(delegator, dispatcher, userLogin, caseValue.caseId,
                stageSeq, stepSeq, locale)
        if (form.error) {
            return errorMap(form.error)
        }
        fields = (form.fields ?: []) as List<Map<String, Object>>
        if (!fields.isEmpty()) {
            formMode = 'DYNAMIC'
        }
    }
    return successMap([
            caseTask  : new LinkedHashMap<>(task.getAllFields()),
            case      : new LinkedHashMap<>(caseValue.getAllFields()),
            caseId    : caseValue.caseId,
            stageSeq  : stageSeq,
            stepSeq   : stepSeq,
            stepForm  : stepForm,
            fields    : fields,
            formMode  : formMode
    ])
}

private Map successMap(Map values) {
    Map result = ServiceUtil.returnSuccess()
    result.putAll(values)
    return result
}

private Map errorMap(String message) {
    return ServiceUtil.returnError(message)
}
