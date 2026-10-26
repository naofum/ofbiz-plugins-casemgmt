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
package org.apache.ofbiz.casemgmt.sla

import java.sql.Timestamp

import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.LocalDispatcher
import org.apache.ofbiz.service.ServiceUtil

/**
 * SLA instance creation, monitoring and completion.
 */
final class CaseSlaHelper {

    private CaseSlaHelper() {
    }

    static Map createSlaInstances(Delegator delegator, LocalDispatcher dispatcher, GenericValue userLogin,
                                  String caseId, Timestamp now) {
        GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', caseId).queryOne()
        if (caseValue == null) {
            return ServiceUtil.returnError("Case not found: $caseId")
        }
        GenericValue caseType = EntityQuery.use(delegator)
                .from('CaseType')
                .where('caseTypeId', caseValue.caseTypeId)
                .queryOne()

        String slaId = selectSlaId(delegator, dispatcher, userLogin, caseValue, caseType)
        if (!slaId) {
            return successMap([created: 0])
        }
        GenericValue sla = EntityQuery.use(delegator).from('CaseSla').where('slaId', slaId).queryOne()
        if (sla == null) {
            return ServiceUtil.returnError("SLA not found: $slaId")
        }
        List<GenericValue> milestones = EntityQuery.use(delegator)
                .from('CaseSlaMilestone')
                .where('slaId', slaId)
                .orderBy('milestoneSeq')
                .queryList()
        if (milestones.isEmpty()) {
            return successMap([created: 0])
        }

        String loginId = userLogin?.userLoginId ?: 'system'
        GenericValue primary = null
        int created = 0
        milestones.each { GenericValue milestone ->
            String calendarId = milestone.calendarId ?: sla.calendarId
            Timestamp goalDate = BusinessCalendarHelper.addMinutes(delegator, calendarId, now,
                    (milestone.goalDuration ?: 0) as long)
            Timestamp deadlineDate = BusinessCalendarHelper.addMinutes(delegator, calendarId, now,
                    (milestone.deadlineDuration ?: 0) as long)
            Timestamp passedDeadlineDate = BusinessCalendarHelper.addMinutes(delegator, calendarId, now,
                    (milestone.passedDeadlineDuration ?: 0) as long)
            delegator.create('CaseSlaInstance', [
                    caseId            : caseId,
                    milestoneSeq      : milestone.milestoneSeq,
                    slaId             : slaId,
                    milestoneName     : milestone.milestoneName,
                    milestoneTypeEnumId: milestone.milestoneTypeEnumId,
                    goalDate          : goalDate,
                    deadlineDate      : deadlineDate,
                    passedDeadlineDate: passedDeadlineDate,
                    slaStateEnumId    : 'SLA_ON_TRACK',
                    pausedMinutes     : 0,
                    escalationLevel   : 0
            ])
            if (primary == null || milestone.milestoneTypeEnumId == 'SLA_RESOLUTION') {
                primary = milestone
            }
            created++
        }

        if (primary != null) {
            GenericValue primaryInstance = EntityQuery.use(delegator)
                    .from('CaseSlaInstance')
                    .where(EntityCondition.makeCondition([
                            caseId      : caseId,
                            milestoneSeq: primary.milestoneSeq
                    ]))
                    .queryOne()
            caseValue.goalDate = primaryInstance.goalDate
            caseValue.deadlineDate = primaryInstance.deadlineDate
            caseValue.passedDeadlineDate = primaryInstance.passedDeadlineDate
            caseValue.slaStateEnumId = 'SLA_ON_TRACK'
            caseValue.store()
        }
        createSlaEvent(delegator, caseId, 'CASE_SLA_STARTED', loginId, now,
                "SLA started: $slaId ($created milestone(s))")
        return successMap([created: created, slaId: slaId])
    }

    static Map monitorSla(Delegator delegator, LocalDispatcher dispatcher, GenericValue userLogin, Timestamp now) {
        String loginId = userLogin?.userLoginId ?: 'system'
        List<GenericValue> instances = EntityQuery.use(delegator)
                .from('CaseSlaInstance')
                .queryList()
                .findAll { GenericValue instance -> instance.completedDate == null }
        int checked = 0
        int escalated = 0
        Set<String> touchedCases = new HashSet<>()

        instances.each { GenericValue instance ->
            checked++
            String observedState = instance.slaStateEnumId ?: 'SLA_ON_TRACK'
            long observedLevel = (instance.escalationLevel ?: 0) as long
            String newState = determineState(instance, now)
            if (newState != observedState) {
                boolean updated = updateInstanceState(delegator, instance.caseId,
                        instance.milestoneSeq as Long, observedState, observedLevel,
                        [slaStateEnumId: newState, lastNotifiedDate: now])
                if (!updated) {
                    touchedCases.add(instance.caseId)
                    return
                }
                String eventType = stateEventType(newState)
                if (eventType) {
                    createSlaEvent(delegator, instance.caseId, eventType, loginId, now,
                            "SLA ${instance.slaId ?: ''} milestone ${instance.milestoneSeq}: $newState")
                }
            }

            int level = escalationLevel(newState)
            if (level > observedLevel) {
                boolean claimed = claimEscalation(delegator, instance.caseId,
                        instance.milestoneSeq as Long, newState, observedLevel, level, now)
                if (!claimed) {
                    touchedCases.add(instance.caseId)
                    return
                }
                String escalationServiceName = findEscalationService(delegator, instance)
                if (escalationServiceName) {
                    try {
                        Map result = dispatcher.runSync(escalationServiceName, [
                                caseId      : instance.caseId,
                                slaId       : instance.slaId,
                                milestoneSeq: instance.milestoneSeq,
                                slaStateEnumId: newState,
                                userLogin   : userLogin
                        ])
                        if (ServiceUtil.isError(result)) {
                            createSlaEvent(delegator, instance.caseId, 'CASE_SLA_BREACHED', loginId, now,
                                    "Escalation service error: ${ServiceUtil.getErrorMessage(result)}")
                        }
                    } catch (Exception e) {
                        createSlaEvent(delegator, instance.caseId, 'CASE_SLA_BREACHED', loginId, now,
                                "Escalation service failed: ${e.message}")
                    }
                }
                createSlaEvent(delegator, instance.caseId, 'CASE_SLA_ESCALATED', loginId, now,
                        "SLA escalated to level $level")
                escalated++
            }
            touchedCases.add(instance.caseId)
        }

        touchedCases.each { String caseId ->
            updateCaseSlaState(delegator, caseId)
        }
        return successMap([checked: checked, escalated: escalated])
    }

    static Map completeMilestoneType(Delegator delegator, GenericValue userLogin, String caseId,
                                     String milestoneTypeEnumId, Timestamp now) {
        String loginId = userLogin?.userLoginId ?: 'system'
        List<GenericValue> instances = EntityQuery.use(delegator)
                .from('CaseSlaInstance')
                .where('caseId', caseId)
                .queryList()
                .findAll { GenericValue instance ->
                    instance.completedDate == null &&
                            instance.milestoneTypeEnumId == milestoneTypeEnumId
                }
        int completed = 0
        instances.each { GenericValue instance ->
            if (completeSlaInstance(delegator, instance.caseId, instance.milestoneSeq as Long, now)) {
                createSlaEvent(delegator, caseId, 'CASE_SLA_COMPLETED', loginId, now,
                        "SLA milestone completed: ${instance.milestoneName ?: instance.milestoneSeq}")
                completed++
            }
        }
        return successMap([completed: completed])
    }

    static Map completeSlaInstances(Delegator delegator, GenericValue userLogin, String caseId, Timestamp now) {
        String loginId = userLogin?.userLoginId ?: 'system'
        List<GenericValue> instances = EntityQuery.use(delegator)
                .from('CaseSlaInstance')
                .where('caseId', caseId)
                .queryList()
                .findAll { GenericValue instance -> instance.completedDate == null }
        int completed = 0
        instances.each { GenericValue instance ->
            if (completeSlaInstance(delegator, instance.caseId, instance.milestoneSeq as Long, now)) {
                createSlaEvent(delegator, caseId, 'CASE_SLA_COMPLETED', loginId, now,
                        "SLA completed: ${instance.slaId ?: ''} milestone ${instance.milestoneSeq}")
                completed++
            }
        }
        if (completed > 0) {
            GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', caseId).queryOne()
            if (caseValue != null) {
                long version = (caseValue.version ?: 0) as long
                delegator.storeByCondition('Case',
                        [slaStateEnumId: 'SLA_COMPLETED', version: version + 1],
                        EntityCondition.makeCondition([
                                caseId : caseId,
                                version: version
                        ]))
            }
        }
        return successMap([completed: completed])
    }

    private static String selectSlaId(Delegator delegator, LocalDispatcher dispatcher, GenericValue userLogin,
                                      GenericValue caseValue, GenericValue caseType) {
        List<GenericValue> rules = EntityQuery.use(delegator)
                .from('CaseSlaRule')
                .where('caseTypeId', caseValue.caseTypeId)
                .orderBy('sequenceNum')
                .queryList()
        for (GenericValue rule : rules) {
            if (rule.conditionServiceName) {
                try {
                    Map result = dispatcher.runSync(rule.conditionServiceName, [
                            caseId    : caseValue.caseId,
                            caseTypeId: caseValue.caseTypeId,
                            userLogin : userLogin
                    ])
                    if (ServiceUtil.isError(result)) {
                        continue
                    }
                    Object value = result.conditionResult
                    if (value == null) {
                        value = result.success
                    }
                    if (!parseCondition(value)) {
                        continue
                    }
                } catch (Exception ignored) {
                    continue
                }
            }
            return rule.slaId
        }
        return caseType?.defaultSlaId
    }

    private static String determineState(GenericValue instance, Timestamp now) {
        if (instance.passedDeadlineDate != null && now.after(instance.passedDeadlineDate)) {
            return 'SLA_PASSED_DEADLINE'
        }
        if (instance.deadlineDate != null && now.after(instance.deadlineDate)) {
            return 'SLA_DEADLINE_MISSED'
        }
        if (instance.goalDate != null && now.after(instance.goalDate)) {
            return 'SLA_GOAL_MISSED'
        }
        return 'SLA_ON_TRACK'
    }

    private static String stateEventType(String state) {
        switch (state) {
            case 'SLA_GOAL_MISSED':
                return 'CASE_SLA_GOAL_MISSED'
            case 'SLA_DEADLINE_MISSED':
                return 'CASE_SLA_OVERDUE'
            case 'SLA_PASSED_DEADLINE':
                return 'CASE_SLA_PASSED'
            default:
                return null
        }
    }

    private static int escalationLevel(String state) {
        switch (state) {
            case 'SLA_GOAL_MISSED':
                return 1
            case 'SLA_DEADLINE_MISSED':
                return 2
            case 'SLA_PASSED_DEADLINE':
                return 3
            default:
                return 0
        }
    }

    private static String findEscalationService(Delegator delegator, GenericValue instance) {
        if (!instance.slaId) {
            return null
        }
        GenericValue milestone = EntityQuery.use(delegator)
                .from('CaseSlaMilestone')
                .where(EntityCondition.makeCondition([
                        slaId       : instance.slaId,
                        milestoneSeq: instance.milestoneSeq
                ]))
                .queryOne()
        if (milestone?.escalationServiceName) {
            return milestone.escalationServiceName
        }
        GenericValue sla = EntityQuery.use(delegator)
                .from('CaseSla')
                .where('slaId', instance.slaId)
                .queryOne()
        return sla?.escalationServiceName
    }

    private static void updateCaseSlaState(Delegator delegator, String caseId) {
        List<GenericValue> instances = EntityQuery.use(delegator)
                .from('CaseSlaInstance')
                .where('caseId', caseId)
                .queryList()
        int worst = -1
        String worstState = null
        instances.each { GenericValue instance ->
            int rank = stateRank(instance.slaStateEnumId)
            if (rank > worst) {
                worst = rank
                worstState = instance.slaStateEnumId
            }
        }
        GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', caseId).queryOne()
        if (caseValue != null && worstState != null && caseValue.slaStateEnumId != worstState) {
            long version = (caseValue.version ?: 0) as long
            delegator.storeByCondition('Case',
                    [slaStateEnumId: worstState, version: version + 1],
                    EntityCondition.makeCondition([
                            caseId : caseId,
                            version: version
                    ]))
        }
    }

    private static int stateRank(String state) {
        switch (state) {
            case 'SLA_PASSED_DEADLINE':
                return 3
            case 'SLA_DEADLINE_MISSED':
                return 2
            case 'SLA_GOAL_MISSED':
                return 1
            case 'SLA_COMPLETED':
                return 0
            default:
                return 0
        }
    }

    static boolean updateInstanceState(Delegator delegator, String caseId, Long milestoneSeq,
                                       String expectedState, long expectedLevel, Map<String, Object> values) {
        return delegator.storeByCondition('CaseSlaInstance', values,
                slaCondition(caseId, milestoneSeq, expectedState, expectedLevel, true)) == 1
    }

    static boolean completeSlaInstance(Delegator delegator, String caseId, Long milestoneSeq, Timestamp now) {
        EntityCondition condition = EntityCondition.makeCondition([
                EntityCondition.makeCondition('caseId', EntityOperator.EQUALS, caseId),
                EntityCondition.makeCondition('milestoneSeq', EntityOperator.EQUALS, milestoneSeq),
                EntityCondition.makeCondition('completedDate', EntityOperator.EQUALS, null)
        ])
        return delegator.storeByCondition('CaseSlaInstance', [
                completedDate  : now,
                slaStateEnumId : 'SLA_COMPLETED'
        ], condition) == 1
    }

    private static boolean claimEscalation(Delegator delegator, String caseId, Long milestoneSeq,
                                           String expectedState, long expectedLevel, long newLevel, Timestamp now) {
        return delegator.storeByCondition('CaseSlaInstance', [
                escalationLevel  : newLevel,
                lastEscalatedDate: now,
                lastNotifiedDate : now
        ], slaCondition(caseId, milestoneSeq, expectedState, expectedLevel, true)) == 1
    }

    private static EntityCondition slaCondition(String caseId, Long milestoneSeq, String expectedState,
                                                long expectedLevel, boolean openOnly) {
        List<EntityCondition> conditions = [
                EntityCondition.makeCondition('caseId', EntityOperator.EQUALS, caseId),
                EntityCondition.makeCondition('milestoneSeq', EntityOperator.EQUALS, milestoneSeq),
                EntityCondition.makeCondition('slaStateEnumId', EntityOperator.EQUALS, expectedState),
                EntityCondition.makeCondition('escalationLevel', EntityOperator.EQUALS, expectedLevel)
        ]
        if (openOnly) {
            conditions << EntityCondition.makeCondition('completedDate', EntityOperator.EQUALS, null)
        }
        return EntityCondition.makeCondition(conditions)
    }

    private static void createSlaEvent(Delegator delegator, String caseId, String eventType,
                                       String loginId, Timestamp now, String detail) {
        delegator.create('CaseEvent', [
                caseEventId      : delegator.getNextSeqId('CaseEvent'),
                caseId           : caseId,
                eventTypeEnumId  : eventType,
                eventDate        : now,
                actorUserLoginId : loginId,
                triggerTypeEnumId: 'CASE_TRIG_SYSTEM',
                engineVersion    : '1.0',
                detail           : detail
        ])
    }

    private static boolean parseCondition(Object value) {
        if (value instanceof Boolean) {
            return value
        }
        return value != null && Boolean.parseBoolean(value.toString())
    }

    private static Map successMap(Map values) {
        Map result = ServiceUtil.returnSuccess()
        result.putAll(values)
        return result
    }
}