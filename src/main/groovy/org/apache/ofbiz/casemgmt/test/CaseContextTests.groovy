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

import org.apache.ofbiz.casemgmt.cases.CaseContextHelper
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * CaseContext link/unlink service tests.
 */
class CaseContextTests extends OFBizTestCase {

    CaseContextTests(String name) {
        super(name)
    }

    private GenericValue user(String userLoginId) {
        return from('UserLogin').where('userLoginId', userLoginId).queryOne()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String createCase() {
        Map result = dispatcher.runSync('createCase', [
                userLogin       : user('system'),
                caseTypeId      : 'PILOT_INQUIRY',
                customerPartyId : 'TEST_CASEMGMT_CUST',
                description     : 'CaseContext test case'
        ])
        ok(result)
        return result.caseId
    }

    void testLinkGetDuplicateAndUnlink() {
        String caseId = createCase()
        Map linked = dispatcher.runSync('linkCaseContext', [
                userLogin        : user('system'),
                caseId           : caseId,
                entityName       : 'Party',
                pkValue          : 'TEST_AGENT1',
                contextTypeEnumId: 'CASE_CTX_RELATED',
                roleTypeId       : 'CAL_OWNER',
                description      : 'Related agent'
        ])
        ok(linked)
        assert linked.linked
        assert linked.contextSeq
        GenericValue singleContext = from('CaseContext')
                .where('caseId', caseId, 'contextSeq', linked.contextSeq).queryOne()
        assert singleContext.pkValue == 'TEST_AGENT1'
        List<GenericValue> singleKeys = from('CaseContextKey')
                .where('caseId', caseId, 'contextSeq', linked.contextSeq).queryList()
        assert singleKeys.size() == 1
        assert singleKeys[0].keyName == 'partyId'
        assert singleKeys[0].keyValue == 'TEST_AGENT1'

        Map listed = dispatcher.runSync('getCaseContext', [userLogin: user('system'), caseId: caseId])
        ok(listed)
        assert listed.contexts.size() == 1
        assert listed.contexts[0].entityName == 'Party'
        assert listed.contexts[0].pkValue == 'TEST_AGENT1'
        assert listed.contexts[0].contextTypeEnumId == 'CASE_CTX_RELATED'
        assert listed.contexts[0].roleTypeId == 'CAL_OWNER'

        Map duplicate = dispatcher.runSync('linkCaseContext', [
                userLogin : user('system'),
                caseId    : caseId,
                entityName: 'Party',
                pkValue   : 'TEST_AGENT1',
                roleTypeId: 'CAL_OWNER'
        ])
        ok(duplicate)
        assert !duplicate.linked
        assert duplicate.contextSeq == linked.contextSeq

        Map unlinked = dispatcher.runSync('unlinkCaseContext', [
                userLogin : user('system'),
                caseId    : caseId,
                contextSeq: linked.contextSeq
        ])
        ok(unlinked)
        assert unlinked.removed

        Map after = dispatcher.runSync('getCaseContext', [userLogin: user('system'), caseId: caseId])
        ok(after)
        assert after.contexts.isEmpty()
    }

    void testCompositeKeyLinkAndDuplicate() {
        String caseId = createCase()
        Map linked = dispatcher.runSync('linkCaseContext', [
                userLogin        : user('system'),
                caseId           : caseId,
                entityName       : 'PartyRole',
                businessKeys     : [partyId: 'TEST_AGENT1', roleTypeId: 'CAL_OWNER'],
                contextTypeEnumId: 'CASE_CTX_RELATED',
                description      : 'Composite related agent'
        ])
        ok(linked)
        assert linked.linked
        assert linked.contextSeq
        assert linked.businessKeys.partyId == 'TEST_AGENT1'
        assert linked.businessKeys.roleTypeId == 'CAL_OWNER'

        GenericValue context = from('CaseContext')
                .where('caseId', caseId, 'contextSeq', linked.contextSeq).queryOne()
        assert context
        assert context.entityName == 'PartyRole'
        assert context.pkValue == null
        List<GenericValue> keys = from('CaseContextKey')
                .where('caseId', caseId, 'contextSeq', linked.contextSeq)
                .orderBy('keySeq').queryList()
        assert keys.size() == 2
        assert keys[0].keyName == 'partyId'
        assert keys[0].keyValue == 'TEST_AGENT1'
        assert keys[1].keyName == 'roleTypeId'
        assert keys[1].keyValue == 'CAL_OWNER'
        Map<String, String> loadedKeys = CaseContextHelper.loadKeys(delegator, context)
        assert loadedKeys == [partyId: 'TEST_AGENT1', roleTypeId: 'CAL_OWNER']
        GenericValue target = CaseContextHelper.findTarget(delegator, 'PartyRole', loadedKeys)
        assert target
        assert target.roleTypeId == 'CAL_OWNER'

        Map listed = dispatcher.runSync('getCaseContext', [userLogin: user('system'), caseId: caseId])
        ok(listed)
        assert listed.contexts.size() == 1
        assert listed.contexts[0].businessKeys == [partyId: 'TEST_AGENT1', roleTypeId: 'CAL_OWNER']

        Map duplicate = dispatcher.runSync('linkCaseContext', [
                userLogin   : user('system'),
                caseId      : caseId,
                entityName  : 'PartyRole',
                businessKeys: [partyId: 'TEST_AGENT1', roleTypeId: 'CAL_OWNER']
        ])
        ok(duplicate)
        assert !duplicate.linked
        assert duplicate.contextSeq == linked.contextSeq

        Map unlinked = dispatcher.runSync('unlinkCaseContext', [
                userLogin : user('system'),
                caseId    : caseId,
                contextSeq: linked.contextSeq
        ])
        ok(unlinked)
        assert from('CaseContextKey').where('caseId', caseId, 'contextSeq', linked.contextSeq).queryOne() == null
        assert from('CaseContext').where('caseId', caseId, 'contextSeq', linked.contextSeq).queryOne() == null
    }

    void testRejectsInvalidTargetAndViewOnlyUser() {
        String caseId = createCase()

        Map invalidEntity = dispatcher.runSync('linkCaseContext', [
                userLogin : user('system'),
                caseId    : caseId,
                entityName: 'NoSuchBusinessEntity',
                pkValue   : 'X'
        ])
        assert ServiceUtil.isError(invalidEntity)

        Map missingTarget = dispatcher.runSync('linkCaseContext', [
                userLogin : user('system'),
                caseId    : caseId,
                entityName: 'Party',
                pkValue   : 'NO_SUCH_PARTY'
        ])
        assert ServiceUtil.isError(missingTarget)

        Map viewOnly = dispatcher.runSync('linkCaseContext', [
                userLogin : user('TEST_CUST_USER'),
                caseId    : caseId,
                entityName: 'Party',
                pkValue   : 'TEST_AGENT1'
        ])
        assert ServiceUtil.isError(viewOnly)
    }
}
