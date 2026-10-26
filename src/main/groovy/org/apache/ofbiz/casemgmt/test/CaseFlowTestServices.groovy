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
package org.apache.ofbiz.casemgmt.test

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.casemgmt.cases.CaseRuntimeHelper

/**
 * Small deterministic services used by the Phase 2 flow tests.
 */
Map conditionTrue() {
    return success([conditionResult: 'true'])
}

Map conditionFalse() {
    return success([conditionResult: 'false'])
}

Map serviceComplete() {
    return success([outcomeEnumId: 'CASE_OUT_COMPLETE', testValue: 'service-output'])
}

Map decisionComplete() {
    return success([
            outcomeEnumId: 'CASE_OUT_COMPLETE',
            receivedValue: parameters.testValue
    ])
}

Map startForm() {
    return success([
            businessEntityName: 'PartyRole',
            businessKeys      : [partyId: 'TEST_AGENT1', roleTypeId: 'CAL_OWNER'],
            customerPartyId   : 'TEST_CASEMGMT_CUST',
            description       : 'Started from test form'
    ])
}

Map taskForm() {
    CaseRuntimeHelper.createCaseEvent(delegator, parameters.caseId, 'CASE_EVENT_CREATED',
            'system', UtilDateTime.nowTimestamp(), [
                    detail       : "task-form:${parameters.taskComment ?: ''}",
                    refEntityName: 'CaseTask',
                    refPkValue   : parameters.caseTaskId
            ])
    return success([outcomeEnumId: 'CASE_OUT_COMPLETE'])
}
