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
package org.apache.ofbiz.casemgmt.access

import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.LocalDispatcher
import org.apache.ofbiz.service.ServiceUtil

/**
 * Case-level access control.
 *
 * Permission levels: VIEW(1) < UPDATE(2) < ASSIGN(3) < CLOSE(4) < ADMIN(5).
 */
final class CaseAccessHelper {

    private static final List<String> OPEN_TASK_STATUSES = [
            'TASK_CREATED', 'TASK_ASSIGNED', 'TASK_IN_PROGRESS', 'TASK_WAITING'
    ]

    private CaseAccessHelper() {
    }

    static Map getCasePermissions(Delegator delegator, LocalDispatcher dispatcher,
                                  GenericValue userLogin, GenericValue caseValue) {
        int level = resolvePermissionLevel(delegator, dispatcher, userLogin, caseValue)
        return [
                permissionLevel: level,
                view           : level >= 1,
                update         : level >= 2,
                assign         : level >= 3,
                close          : level >= 4,
                admin          : level >= 5
        ]
    }

    static boolean hasCasePermission(Delegator delegator, LocalDispatcher dispatcher,
                                     GenericValue userLogin, GenericValue caseValue, String permissionEnumId) {
        int required = rank(permissionEnumId)
        return resolvePermissionLevel(delegator, dispatcher, userLogin, caseValue) >= required
    }

    static String permissionError(GenericValue userLogin, GenericValue caseValue, String permissionEnumId) {
        return "User ${userLogin?.userLoginId ?: 'unknown'} does not have $permissionEnumId on case ${caseValue?.caseId}"
    }

    static List<GenericValue> filterVisibleCases(Delegator delegator, LocalDispatcher dispatcher,
                                                 GenericValue userLogin, List<GenericValue> cases) {
        return cases.findAll { GenericValue caseValue ->
            hasCasePermission(delegator, dispatcher, userLogin, caseValue, 'CASE_VIEW')
        }
    }

    private static int resolvePermissionLevel(Delegator delegator, LocalDispatcher dispatcher,
                                              GenericValue userLogin, GenericValue caseValue) {
        if (userLogin == null || caseValue == null) {
            return 0
        }
        if (isCaseAdmin(delegator, userLogin)) {
            return 5
        }
        String partyId = userLogin.partyId
        int level = 0
        if (partyId && partyId == caseValue.ownerPartyId) {
            level = Math.max(level, 4)
        }
        if (partyId && partyId == caseValue.customerPartyId) {
            level = Math.max(level, 1)
        }

        List<GenericValue> tasks = EntityQuery.use(delegator)
                .from('CaseTask')
                .where('caseId', caseValue.caseId)
                .queryList()
                .findAll { GenericValue task -> OPEN_TASK_STATUSES.contains(task.statusId) }
        tasks.each { GenericValue task ->
            if (partyId && task.assignedPartyId == partyId) {
                level = Math.max(level, 2)
            }
            if (task.assignedQueueId && partyId && isQueueMember(delegator, task.assignedQueueId, partyId)) {
                level = Math.max(level, 2)
            }
        }

        String policyId = caseValue.accessPolicyId
        if (!policyId) {
            GenericValue caseType = EntityQuery.use(delegator)
                    .from('CaseType')
                    .where('caseTypeId', caseValue.caseTypeId)
                    .queryOne()
            policyId = caseType?.accessPolicyId
        }
        if (!policyId) {
            return level
        }
        List<GenericValue> rules = EntityQuery.use(delegator)
                .from('CaseAccessRule')
                .where('accessPolicyId', policyId)
                .orderBy('ruleSeq')
                .queryList()
        for (GenericValue rule : rules) {
            if (!ruleMatches(delegator, dispatcher, userLogin, caseValue, rule)) {
                continue
            }
            level = Math.max(level, rank(rule.permissionEnumId))
        }
        return level
    }

    private static boolean ruleMatches(Delegator delegator, LocalDispatcher dispatcher,
                                       GenericValue userLogin, GenericValue caseValue, GenericValue rule) {
        String partyId = userLogin.partyId
        if (rule.partyId && rule.partyId != partyId) {
            return false
        }
        if (rule.partyRoleTypeId && !hasPartyRole(delegator, partyId, rule.partyRoleTypeId)) {
            return false
        }
        if (rule.queueId && !isQueueMember(delegator, rule.queueId, partyId)) {
            return false
        }
        if (rule.stageSeq != null && caseValue.currentStageSeq != rule.stageSeq) {
            return false
        }
        if (rule.stepSeq != null && caseValue.currentStepSeq != rule.stepSeq) {
            return false
        }
        if (rule.conditionServiceName) {
            try {
                Map result = dispatcher.runSync(rule.conditionServiceName, [
                        caseId    : caseValue.caseId,
                        caseTypeId: caseValue.caseTypeId,
                        partyId   : partyId,
                        userLogin : userLogin
                ])
                if (ServiceUtil.isError(result)) {
                    return false
                }
                Object value = result.conditionResult
                if (value == null) {
                    value = result.success
                }
                return parseCondition(value)
            } catch (Exception ignored) {
                return false
            }
        }
        return true
    }

    private static boolean isCaseAdmin(Delegator delegator, GenericValue userLogin) {
        List<GenericValue> groups = EntityQuery.use(delegator)
                .from('UserLoginSecurityGroup')
                .where('userLoginId', userLogin.userLoginId)
                .queryList()
        for (GenericValue group : groups) {
            GenericValue permission = EntityQuery.use(delegator)
                    .from('SecurityGroupPermission')
                    .where(EntityCondition.makeCondition([
                            groupId     : group.groupId,
                            permissionId: 'CASEMGMT_ADMIN'
                    ]))
                    .queryOne()
            if (permission != null) {
                return true
            }
        }
        return false
    }

    private static boolean hasPartyRole(Delegator delegator, String partyId, String roleTypeId) {
        if (!partyId) {
            return false
        }
        return EntityQuery.use(delegator)
                .from('PartyRole')
                .where(EntityCondition.makeCondition([
                        partyId    : partyId,
                        roleTypeId : roleTypeId
                ]))
                .queryOne() != null
    }

    private static boolean isQueueMember(Delegator delegator, String queueId, String partyId) {
        if (!partyId) {
            return false
        }
        return EntityQuery.use(delegator)
                .from('CaseQueueMember')
                .where(EntityCondition.makeCondition([
                        queueId: queueId,
                        partyId: partyId
                ]))
                .queryOne() != null
    }

    private static int rank(String permissionEnumId) {
        switch (permissionEnumId) {
            case 'CASE_ADMIN':
                return 5
            case 'CASE_CLOSE':
                return 4
            case 'CASE_ASSIGN':
                return 3
            case 'CASE_UPDATE':
                return 2
            case 'CASE_VIEW':
                return 1
            default:
                return 0
        }
    }

    private static boolean parseCondition(Object value) {
        if (value instanceof Boolean) {
            return value
        }
        return value != null && Boolean.parseBoolean(value.toString())
    }
}
