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

import java.sql.Timestamp

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.casemgmt.sla.BusinessCalendarHelper
import org.apache.ofbiz.casemgmt.sla.CaseSlaHelper
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Phase 2 SLA tests.
 */
class CaseSlaTests extends OFBizTestCase {

    CaseSlaTests(String name) {
        super(name)
    }

    private GenericValue system() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String createCase() {
        Map result = dispatcher.runSync('createCase', [
                userLogin       : system(),
                caseTypeId      : 'TEST_SLA',
                customerPartyId : 'TEST_CASEMGMT_CUST',
                description     : 'SLA test case'
        ])
        ok(result)
        assert result.caseId
        return result.caseId
    }

    void testSlaInstancesAreCreated() {
        String caseId = createCase()
        List<GenericValue> instances = from('CaseSlaInstance').where('caseId', caseId).queryList()
        assert instances.size() == 1
        GenericValue instance = instances[0]
        assert instance.slaId == 'TEST_SLA'
        assert instance.milestoneName == 'Resolution'
        assert instance.slaStateEnumId == 'SLA_ON_TRACK'
        assert instance.goalDate
        assert instance.deadlineDate
        assert instance.passedDeadlineDate

        GenericValue caseValue = from('Case').where('caseId', caseId).queryOne()
        assert caseValue.goalDate
        assert caseValue.slaStateEnumId == 'SLA_ON_TRACK'
    }

    void testMonitorEscalatesAndIsIdempotent() {
        String caseId = createCase()
        GenericValue instance = from('CaseSlaInstance').where('caseId', caseId).queryOne()
        Timestamp now = UtilDateTime.nowTimestamp()
        instance.goalDate = new Timestamp(now.time - 3L * 60L * 60L * 1000L)
        instance.deadlineDate = new Timestamp(now.time - 2L * 60L * 60L * 1000L)
        instance.passedDeadlineDate = new Timestamp(now.time - 60L * 60L * 1000L)
        instance.store()

        Map first = dispatcher.runSync('monitorCaseSla', [userLogin: system()])
        ok(first)
        assert first.checked >= 1
        assert first.escalated >= 1

        GenericValue monitored = from('CaseSlaInstance').where('caseId', caseId).queryOne()
        assert monitored.slaStateEnumId == 'SLA_PASSED_DEADLINE'
        assert (monitored.escalationLevel as long) == 3L
        assert monitored.lastEscalatedDate
        List<GenericValue> events = from('CaseEvent')
                .where('caseId', caseId, 'eventTypeEnumId', 'CASE_SLA_PASSED')
                .queryList()
        assert events.size() == 1

        Map second = dispatcher.runSync('monitorCaseSla', [userLogin: system()])
        ok(second)
        assert second.escalated == 0
        List<GenericValue> escalations = from('CaseEvent')
                .where('caseId', caseId, 'eventTypeEnumId', 'CASE_SLA_ESCALATED')
                .queryList()
        assert escalations.size() == 1
    }

    void testCloseCompletesSla() {
        String caseId = createCase()
        Map result = dispatcher.runSync('closeCase', [
                userLogin    : system(),
                caseId       : caseId,
                resolutionId : 'TEST_RESOLVED'
        ])
        ok(result)

        GenericValue instance = from('CaseSlaInstance').where('caseId', caseId).queryOne()
        assert instance.slaStateEnumId == 'SLA_COMPLETED'
        assert instance.completedDate
        GenericValue caseValue = from('Case').where('caseId', caseId).queryOne()
        assert caseValue.slaStateEnumId == 'SLA_COMPLETED'
    }

    void testConditionalSlaInstanceUpdate() {
        String caseId = createCase()
        GenericValue instance = from('CaseSlaInstance').where('caseId', caseId).queryOne()
        long level = (instance.escalationLevel ?: 0) as long
        Timestamp now = UtilDateTime.nowTimestamp()
        Map<String, Object> values = [
                slaStateEnumId : 'SLA_GOAL_MISSED',
                lastNotifiedDate: now
        ]
        assert CaseSlaHelper.updateInstanceState(delegator, caseId, instance.milestoneSeq as Long,
                'SLA_ON_TRACK', level, values)
        assert !CaseSlaHelper.updateInstanceState(delegator, caseId, instance.milestoneSeq as Long,
                'SLA_ON_TRACK', level, values)
        GenericValue updated = from('CaseSlaInstance').where('caseId', caseId).queryOne()
        assert updated.slaStateEnumId == 'SLA_GOAL_MISSED'

        assert CaseSlaHelper.completeSlaInstance(delegator, caseId, instance.milestoneSeq as Long, now)
        assert !CaseSlaHelper.completeSlaInstance(delegator, caseId, instance.milestoneSeq as Long, now)
        GenericValue completed = from('CaseSlaInstance').where('caseId', caseId).queryOne()
        assert completed.slaStateEnumId == 'SLA_COMPLETED'
        assert completed.completedDate
    }

    void testBusinessCalendarAddMinutes() {
        Timestamp monday = Timestamp.valueOf('2026-10-26 09:30:00')
        Timestamp mondayResult = BusinessCalendarHelper.addMinutes(delegator,
                'CASE_CAL_DEFAULT', monday, 120)
        assert mondayResult.time == Timestamp.valueOf('2026-10-26 11:30:00').time

        Timestamp friday = Timestamp.valueOf('2026-10-30 16:00:00')
        Timestamp fridayResult = BusinessCalendarHelper.addMinutes(delegator,
                'CASE_CAL_DEFAULT', friday, 120)
        assert fridayResult.time == Timestamp.valueOf('2026-11-02 10:00:00').time
    }
}
