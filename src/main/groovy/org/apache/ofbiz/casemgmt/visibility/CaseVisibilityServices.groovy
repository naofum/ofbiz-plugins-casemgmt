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
package org.apache.ofbiz.casemgmt.visibility

import org.apache.ofbiz.casemgmt.access.CaseAccessHelper
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

Map getCaseProcessMap() {
    GenericValue caseValue = EntityQuery.use(delegator)
            .from('Case')
            .where('caseId', parameters.caseId)
            .queryOne()
    if (caseValue == null) {
        return errorMap("Case not found: ${parameters.caseId}")
    }
    if (!CaseAccessHelper.hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_VIEW')) {
        return errorMap(CaseAccessHelper.permissionError(userLogin, caseValue, 'CASE_VIEW'))
    }
    return successMap(CaseProcessHelper.buildProcessMap(delegator, parameters.caseId))
}

Map getCaseStack() {
    return successMap(CaseProcessHelper.buildStack(delegator, parameters))
}

Map getCaseMetrics() {
    Map metrics = CaseReportHelper.buildMetrics(delegator, [
            caseTypeId     : parameters.caseTypeId,
            statusId       : parameters.statusId,
            stageSeq       : parameters.stageSeq,
            queueId        : parameters.queueId,
            assignedPartyId: parameters.assignedPartyId,
            fromDate       : parameters.fromDate,
            toDate         : parameters.toDate
    ])
    return successMap(metrics)
}

Map exportCaseMetrics() {
    Map metrics = CaseReportHelper.buildMetrics(delegator, [
            caseTypeId     : parameters.caseTypeId,
            statusId       : parameters.statusId,
            stageSeq       : parameters.stageSeq,
            queueId        : parameters.queueId,
            assignedPartyId: parameters.assignedPartyId,
            fromDate       : parameters.fromDate,
            toDate         : parameters.toDate
    ])
    return successMap([csv: CaseReportHelper.toCsv(metrics)])
}

Map getCaseTypeProcessMap() {
    Map result = CaseProcessHelper.buildCaseTypeProcessMap(delegator, parameters.caseTypeId,
            parameters.definitionVersion as Long)
    if (result.error) {
        return errorMap(result.error)
    }
    return successMap(result)
}

Map simulateCaseFlow() {
    Map result = CaseProcessHelper.simulate(delegator, parameters.caseTypeId,
            parameters.definitionVersion as Long,
            parameters.outcomeEnumId,
            (parameters.maxSteps ?: 50) as int)
    if (result.error) {
        return errorMap(result.error)
    }
    return successMap(result)
}

private Map successMap(Map values) {
    Map result = ServiceUtil.returnSuccess()
    result.putAll(values)
    return result
}

private Map errorMap(String message) {
    return ServiceUtil.returnError(message)
}
