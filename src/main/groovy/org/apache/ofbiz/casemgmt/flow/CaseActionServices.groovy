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
package org.apache.ofbiz.casemgmt.flow

import java.sql.Timestamp

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.casemgmt.cases.CaseRuntimeHelper
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

/**
 * Production service-step actions used by case definitions.
 */
Map provisionServiceRequest() {
    GenericValue caseValue = EntityQuery.use(delegator)
            .from('Case')
            .where('caseId', parameters.caseId)
            .queryOne()
    if (caseValue == null) {
        return ServiceUtil.returnError("Case not found: ${parameters.caseId}")
    }
    Timestamp now = UtilDateTime.nowTimestamp()
    String loginId = userLogin?.userLoginId ?: 'system'
    CaseRuntimeHelper.createCaseEvent(delegator, caseValue.caseId, 'CASE_SERVICE_DONE',
            loginId, now, [
                    fromStageSeq : parameters.stageSeq,
                    stageVisitSeq: parameters.stageVisitSeq,
                    fromStepSeq  : parameters.stepSeq,
                    detail       : 'Service request provisioned'
            ])
    Map result = ServiceUtil.returnSuccess()
    result.outcomeEnumId = 'CASE_OUT_COMPLETE'
    return result
}
