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
package org.apache.ofbiz.casemgmt.casetype

import org.apache.ofbiz.entity.GenericValue

/**
 * Draft state of a case type.
 *
 * <p>{@code CaseType.statusId} describes publication only: DRAFT (never published), ACTIVE
 * (published, also while a new draft is edited) or RETIRED. Whether an editable draft exists is
 * derived from the versions: a never-published DRAFT type, or {@code definitionVersion} newer than
 * {@code activeDefinitionVersion}. Keeping a published type ACTIVE while its next version is drafted
 * lets new cases and sub-cases keep starting on the published version.</p>
 */
class CaseTypeDraftHelper {

    static final String DRAFT = 'CASE_TYPE_DRAFT'
    static final String ACTIVE = 'CASE_TYPE_ACTIVE'
    static final String RETIRED = 'CASE_TYPE_RETIRED'

    static boolean isPublished(GenericValue caseType) {
        return caseType != null && caseType.activeDefinitionVersion != null
    }

    /** True when the case type has a version that may be edited. */
    static boolean hasEditableDraft(GenericValue caseType) {
        if (caseType == null || caseType.statusId == RETIRED) {
            return false
        }
        if (!isPublished(caseType)) {
            return caseType.statusId == DRAFT
        }
        return draftVersion(caseType) > (caseType.activeDefinitionVersion as long)
    }

    /** True when the version is the editable draft. */
    static boolean isEditableVersion(GenericValue caseType, long version) {
        return hasEditableDraft(caseType) && version == draftVersion(caseType)
    }

    static long draftVersion(GenericValue caseType) {
        return (caseType.definitionVersion ?: 1) as long
    }
}
