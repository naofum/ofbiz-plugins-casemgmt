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
package org.apache.ofbiz.casemgmt.form

import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.LocalDispatcher
import org.apache.ofbiz.widget.model.FormFactory
import org.apache.ofbiz.widget.model.ModelForm
import org.apache.ofbiz.widget.model.ThemeFactory

/**
 * Resolves start/task form metadata and validates that a form definition uses
 * the case-specific submit target rather than a standard OFBiz target.
 */
final class CaseFormMetadataHelper {

    static final String START_FORM_TARGET = 'StartCaseSubmit'
    static final String TASK_FORM_TARGET = 'TaskFormSubmit'

    private CaseFormMetadataHelper() {
    }

    static long definitionVersion(GenericValue caseType) {
        return (caseType?.activeDefinitionVersion ?: caseType?.definitionVersion ?: 1) as long
    }

    static Map<String, Object> loadStartForm(Delegator delegator, String caseTypeId, long version) {
        GenericValue form = EntityQuery.use(delegator)
                .from('CaseTypeStartForm')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : caseTypeId,
                        definitionVersion: version
                ]))
                .queryOne()
        return toMap(form)
    }

    static Map<String, Object> loadStepForm(Delegator delegator, String caseTypeId, long version,
                                            long stageSeq, long stepSeq) {
        GenericValue form = EntityQuery.use(delegator)
                .from('CaseStepForm')
                .where(EntityCondition.makeCondition([
                        caseTypeId       : caseTypeId,
                        definitionVersion: version,
                        stageSeq         : stageSeq,
                        stepSeq          : stepSeq
                ]))
                .queryOne()
        return toMap(form)
    }

    static String validateFormDefinition(Delegator delegator, LocalDispatcher dispatcher,
                                         String formLocation, String formName, String expectedTarget) {
        if (!formLocation?.trim() || !formName?.trim()) {
            return 'Form location and form name are required'
        }
        try {
            ModelForm modelForm = FormFactory.getFormFromLocation(
                    formLocation.trim(),
                    formName.trim(),
                    delegator.getModelReader(),
                    ThemeFactory.getVisualThemeFromId('COMMON'),
                    dispatcher.getDispatchContext())
            String target = modelForm.getTarget()
            if (!target) {
                return "Form target is required: $formName"
            }
            int queryIndex = target.indexOf('?')
            String baseTarget = queryIndex >= 0 ? target.substring(0, queryIndex) : target
            if (baseTarget != expectedTarget) {
                return "Form target must be $expectedTarget: $formName is $baseTarget"
            }
        } catch (Exception e) {
            return "Form definition could not be loaded: $formLocation#$formName: ${e.message}"
        }
        return null
    }

    static Map<String, Object> toMap(GenericValue value) {
        return value == null ? [:] : new LinkedHashMap<>(value.getAllFields())
    }
}
