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

Map getCaseForm() {
    GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', parameters.caseId).queryOne()
    if (caseValue == null) {
        return errorMap("Case not found: ${parameters.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_VIEW')) {
        return errorMap(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_VIEW'))
    }
    Map form = CaseFormHelper.getForm(delegator, dispatcher, userLogin, parameters.caseId,
            parameters.stageSeq as Long, parameters.stepSeq as Long, locale)
    if (form.error) {
        return errorMap(form.error)
    }
    return successMap(form)
}

Map saveCaseForm() {
    GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', parameters.caseId).queryOne()
    if (caseValue == null) {
        return errorMap("Case not found: ${parameters.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_UPDATE')) {
        return errorMap(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_UPDATE'))
    }
    Map values = [:]
    if (parameters.values instanceof Map) {
        values.putAll(parameters.values as Map)
    }
    parameters.each { String key, Object value ->
        if (key.startsWith('field_')) {
            values[key] = value
        }
    }
    Map result = CaseFormHelper.saveForm(delegator, dispatcher, userLogin, parameters.caseId,
            parameters.stageSeq as Long, parameters.stepSeq as Long, values, false)
    if (result.errors) {
        return errorMap(result.errors.join('; '))
    }
    return successMap([saved: result.saved])
}

Map validateCaseForm() {
    GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', parameters.caseId).queryOne()
    if (caseValue == null) {
        return errorMap("Case not found: ${parameters.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_VIEW')) {
        return errorMap(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_VIEW'))
    }
    Map values = [:]
    if (parameters.values instanceof Map) {
        values.putAll(parameters.values as Map)
    }
    parameters.each { String key, Object value ->
        if (key.startsWith('field_')) {
            values[key] = value
        }
    }
    Map result = CaseFormHelper.saveForm(delegator, dispatcher, userLogin, parameters.caseId,
            parameters.stageSeq as Long, parameters.stepSeq as Long, values, true)
    if (result.errors) {
        return errorMap(result.errors.join('; '))
    }
    return successMap([valid: true])
}

private Map successMap(Map values) {
    Map result = ServiceUtil.returnSuccess()
    result.putAll(values)
    return result
}

private Map errorMap(String message) {
    return ServiceUtil.returnError(message)
}
