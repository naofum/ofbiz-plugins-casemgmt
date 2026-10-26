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

import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.entity.util.EntityQuery

/**
 * Small compare-and-set helpers for cluster-safe case operations.
 *
 * The first update succeeds and holds the row lock until commit. Concurrent
 * callers see zero updated rows and must fail or retry instead of continuing
 * with stale state.
 */
final class CaseConcurrencyHelper {

    private CaseConcurrencyHelper() {
    }

    static boolean lockCase(Delegator delegator, String caseId) {
        GenericValue caseValue = EntityQuery.use(delegator)
                .from('Case')
                .where('caseId', caseId)
                .queryOne()
        if (caseValue == null) {
            return false
        }
        long version = (caseValue.version ?: 0) as long
        int updated = delegator.storeByCondition('Case', [version: version + 1],
                EntityCondition.makeCondition([
                        caseId : caseId,
                        version: version
                ]))
        return updated == 1
    }

    static boolean updateTask(Delegator delegator, String caseTaskId, String expectedStatus,
                              Map<String, Object> values) {
        int updated = delegator.storeByCondition('CaseTask', values,
                EntityCondition.makeCondition([
                        caseTaskId: caseTaskId,
                        statusId  : expectedStatus
                ]))
        return updated == 1
    }

    static boolean claimTask(Delegator delegator, String caseTaskId, String expectedStatus,
                            Map<String, Object> values) {
        EntityCondition condition = EntityCondition.makeCondition([
                EntityCondition.makeCondition('caseTaskId', EntityOperator.EQUALS, caseTaskId),
                EntityCondition.makeCondition('statusId', EntityOperator.EQUALS, expectedStatus),
                EntityCondition.makeCondition('assignedPartyId', EntityOperator.EQUALS, null)
        ])
        return delegator.storeByCondition('CaseTask', values, condition) == 1
    }

    static boolean releaseTask(Delegator delegator, String caseTaskId, String expectedStatus,
                               String expectedPartyId, Map<String, Object> values) {
        EntityCondition condition = EntityCondition.makeCondition([
                EntityCondition.makeCondition('caseTaskId', EntityOperator.EQUALS, caseTaskId),
                EntityCondition.makeCondition('statusId', EntityOperator.EQUALS, expectedStatus),
                EntityCondition.makeCondition('assignedPartyId', EntityOperator.EQUALS, expectedPartyId)
        ])
        return delegator.storeByCondition('CaseTask', values, condition) == 1
    }
}
