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
package org.apache.ofbiz.casemgmt.assignment

import java.sql.Timestamp

import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery

/**
 * Queue and assignment policy evaluation.
 */
final class CaseAssignmentHelper {

    private static final List<String> OPEN_TASK_STATUSES = [
            'TASK_CREATED', 'TASK_ASSIGNED', 'TASK_IN_PROGRESS', 'TASK_WAITING'
    ]

    private CaseAssignmentHelper() {
    }

    static Map resolveAssignment(Delegator delegator, GenericValue step, String fallbackPartyId, Timestamp now) {
        return resolveAssignment(delegator, step?.assignmentPolicyId, fallbackPartyId, now)
    }

    static Map resolveAssignment(Delegator delegator, String policyId, String fallbackPartyId, Timestamp now) {
        if (!policyId) {
            return [assignedPartyId: fallbackPartyId, assignedQueueId: null]
        }
        GenericValue policy = EntityQuery.use(delegator)
                .from('CaseAssignmentPolicy')
                .where('assignmentPolicyId', policyId)
                .queryOne()
        if (policy == null) {
            return [assignedPartyId: fallbackPartyId, assignedQueueId: null]
        }

        String strategy = policy.strategyEnumId
        String queueId = policy.queueId
        String partyId = null
        switch (strategy) {
            case 'CASE_Q_SPECIFIC':
                partyId = policy.specificPartyId
                break
            case 'CASE_Q_PICK':
                break
            case 'CASE_Q_ROUND_ROBIN':
                partyId = selectRoundRobin(delegator, queueId, now)
                break
            case 'CASE_Q_LEAST_LOAD':
                partyId = selectLeastLoaded(delegator, queueId, null, now)
                break
            case 'CASE_Q_SKILL':
                partyId = selectLeastLoaded(delegator, queueId, policy.requiredSkillTypeId, now)
                break
            default:
                partyId = fallbackPartyId
        }
        if (partyId == null && strategy == 'CASE_Q_SPECIFIC') {
            partyId = fallbackPartyId
        }
        return [assignedPartyId: partyId, assignedQueueId: queueId]
    }

    static List<GenericValue> getActiveMembers(Delegator delegator, String queueId, Timestamp now) {
        if (!queueId) {
            return []
        }
        return EntityQuery.use(delegator)
                .from('CaseQueueMember')
                .where('queueId', queueId)
                .queryList()
                .findAll { GenericValue member ->
                    member.activeIndicator != 'N' &&
                            (member.fromDate == null || !member.fromDate.after(now)) &&
                            (member.thruDate == null || member.thruDate.after(now))
                }
    }

    static long countOpenTasks(Delegator delegator, String partyId) {
        return EntityQuery.use(delegator)
                .from('CaseTask')
                .where('assignedPartyId', partyId)
                .queryList()
                .count { GenericValue task -> OPEN_TASK_STATUSES.contains(task.statusId) }
    }

    static Map getQueueWorkload(Delegator delegator, String queueId, Timestamp now) {
        List<Map<String, Object>> members = getActiveMembers(delegator, queueId, now).collect { GenericValue member ->
            [
                    partyId         : member.partyId,
                    openTasks       : countOpenTasks(delegator, member.partyId),
                    lastAssignedDate: member.lastAssignedDate,
                    weight          : member.weight
            ]
        }
        return [queueId: queueId, members: members]
    }

    private static String selectRoundRobin(Delegator delegator, String queueId, Timestamp now) {
        List<GenericValue> members = getActiveMembers(delegator, queueId, now)
        if (members.isEmpty()) {
            return null
        }
        members.sort { GenericValue left, GenericValue right ->
            long leftTime = left.lastAssignedDate == null ? 0L : left.lastAssignedDate.time
            long rightTime = right.lastAssignedDate == null ? 0L : right.lastAssignedDate.time
            return leftTime <=> rightTime
        }
        GenericValue selected = members[0]
        selected.lastAssignedDate = now
        selected.store()
        return selected.partyId
    }

    private static String selectLeastLoaded(Delegator delegator, String queueId, String requiredSkillTypeId,
                                            Timestamp now) {
        List<GenericValue> members = getActiveMembers(delegator, queueId, now)
        if (requiredSkillTypeId) {
            members = members.findAll { GenericValue member ->
                EntityQuery.use(delegator)
                        .from('PartySkill')
                        .where(EntityCondition.makeCondition([
                                partyId    : member.partyId,
                                skillTypeId: requiredSkillTypeId
                        ]))
                        .queryOne() != null
            }
        }
        if (members.isEmpty()) {
            return null
        }
        members.sort { GenericValue left, GenericValue right ->
            long leftLoad = countOpenTasks(delegator, left.partyId)
            long rightLoad = countOpenTasks(delegator, right.partyId)
            int byLoad = leftLoad <=> rightLoad
            if (byLoad != 0) {
                return byLoad
            }
            long leftTime = left.lastAssignedDate == null ? 0L : left.lastAssignedDate.time
            long rightTime = right.lastAssignedDate == null ? 0L : right.lastAssignedDate.time
            return leftTime <=> rightTime
        }
        GenericValue selected = members[0]
        selected.lastAssignedDate = now
        selected.store()
        return selected.partyId
    }
}
