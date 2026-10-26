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

import java.util.Locale

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Phase 2 dynamic form tests.
 */
class CaseFormTests extends OFBizTestCase {

    CaseFormTests(String name) {
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
                userLogin : system(),
                caseTypeId: 'TEST_FORM'
        ])
        ok(result)
        return result.caseId
    }

    void testGetCaseForm() {
        String caseId = createCase()
        Map result = dispatcher.runSync('getCaseForm', [userLogin: system(), caseId: caseId])
        ok(result)
        assert result.fields.size() == 2
        assert result.fields[0].fieldName == 'description'
        assert result.fields[0].fieldTypeEnumId == 'CASE_FIELD_TEXTAREA'
        assert result.fields[0].requiredIndicator == 'Y'
        assert result.fields[0].visible
    }

    void testSaveCaseForm() {
        String caseId = createCase()
        Map result = dispatcher.runSync('saveCaseForm', [
                userLogin       : system(),
                caseId          : caseId,
                stageSeq        : 1,
                stepSeq         : 1,
                values          : [description: 'Updated description', priority: '5']
        ])
        ok(result)
        assert result.saved == 2
        GenericValue caseValue = from('Case').where('caseId', caseId).queryOne()
        assert caseValue.description == 'Updated description'
        assert (caseValue.priority as long) == 5L
    }

    void testRequiredFieldValidation() {
        String caseId = createCase()
        Map result = dispatcher.runSync('validateCaseForm', [
                userLogin       : system(),
                caseId          : caseId,
                stageSeq        : 1,
                stepSeq         : 1,
                values          : [description: '', priority: '2']
        ])
        assert ServiceUtil.isError(result)
    }

    void testLocalizedFieldLabels() {
        List<GenericValue> mappings = from('CaseFieldMapping')
                .where('caseTypeId', 'TEST_FORM', 'definitionVersion', 1L,
                        'stageSeq', 1L, 'stepSeq', 1L)
                .orderBy('fieldSeq')
                .queryList()
        assert mappings.size() == 2
        long descriptionSeq = mappings[0].fieldSeq as long
        long prioritySeq = mappings[1].fieldSeq as long
        delegator.create('CaseFieldLabel', [
                caseTypeId       : 'TEST_FORM',
                definitionVersion: 1L,
                stageSeq         : 1L,
                stepSeq          : 1L,
                fieldSeq         : descriptionSeq,
                locale           : 'ja',
                label            : '内容'
        ])
        delegator.create('CaseFieldLabel', [
                caseTypeId       : 'TEST_FORM',
                definitionVersion: 1L,
                stageSeq         : 1L,
                stepSeq          : 1L,
                fieldSeq         : descriptionSeq,
                locale           : 'en',
                label            : 'Description (EN)'
        ])
        delegator.create('CaseFieldLabel', [
                caseTypeId       : 'TEST_FORM',
                definitionVersion: 1L,
                stageSeq         : 1L,
                stepSeq          : 1L,
                fieldSeq         : prioritySeq,
                locale           : 'ja_JP',
                label            : '優先度'
        ])

        String caseId = createCase()
        Map japanese = dispatcher.runSync('getCaseForm', [
                userLogin: system(),
                caseId   : caseId,
                locale   : Locale.JAPAN
        ])
        ok(japanese)
        assert fieldLabel(japanese, 'description') == '内容'
        assert fieldLabel(japanese, 'priority') == '優先度'

        Map english = dispatcher.runSync('getCaseForm', [
                userLogin: system(),
                caseId   : caseId,
                locale   : Locale.US
        ])
        ok(english)
        assert fieldLabel(english, 'description') == 'Description (EN)'

        Map fallback = dispatcher.runSync('getCaseForm', [
                userLogin: system(),
                caseId   : caseId,
                locale   : Locale.GERMAN
        ])
        ok(fallback)
        assert fieldLabel(fallback, 'description') == 'Description'
        assert fieldLabel(fallback, 'priority') == 'Priority'

        from('CaseFieldLabel').where('caseTypeId', 'TEST_FORM').queryList()
                .each { GenericValue label -> delegator.removeValue(label) }
    }

    private String fieldLabel(Map form, String fieldName) {
        Map field = (form.fields as List<Map>).find { Map value -> value.fieldName == fieldName }
        return field?.label
    }
}
