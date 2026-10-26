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
package org.apache.ofbiz.casemgmt.workeffort

import org.apache.ofbiz.entity.GenericValue

/**
 * Entity ECA guard and WorkEffort hierarchy validation.
 */
Map guardCaseWorkEffortWrite() {
    if (!CaseWorkEffortContext.isAuthorized()) {
        return error(
                'CASE work efforts must be written through casemgmt services')
    }
    return success()
}

Map validateCaseWorkEffortTree() {
    String workEffortId = parameters.workEffortId
    List<String> errors = []

    GenericValue workEffort = from('WorkEffort').where(workEffortId: workEffortId).queryOne()
    if (workEffort == null) {
        return error("WorkEffort not found: $workEffortId")
    }
    if (workEffort.workEffortTypeId != 'CASE') {
        errors << "WorkEffort $workEffortId is not CASE"
    }

    GenericValue caseValue = from('Case').where(caseId: workEffortId).queryOne()
    if (caseValue == null) {
        errors << "Case row not found for CASE WorkEffort $workEffortId"
    }

    List<GenericValue> children = from('WorkEffort')
            .where(workEffortParentId: workEffortId)
            .queryList()
    children.each { GenericValue child ->
        if (child.workEffortTypeId == 'CASE_ASSIGNMENT') {
            GenericValue caseTask = from('CaseTask')
                    .where('workEffortId', child.workEffortId)
                    .queryOne()
            if (caseTask == null) {
                errors << "CaseTask not found for CASE_ASSIGNMENT ${child.workEffortId}"
            } else if (caseTask.caseId != workEffortId) {
                errors << "CaseTask.caseId mismatch for CASE_ASSIGNMENT ${child.workEffortId}"
            }
        } else if (child.workEffortTypeId == 'CASE') {
            if (from('Case').where(caseId: child.workEffortId).queryOne() == null) {
                errors << "Sub-case ${child.workEffortId} has no Case row"
            }
        } else {
            errors << "Unexpected child WorkEffort type ${child.workEffortTypeId} under CASE $workEffortId"
        }
    }

    Set<String> seen = new HashSet<>()
    GenericValue current = workEffort
    while (current?.workEffortParentId) {
        if (!seen.add(current.workEffortId)) {
            errors << 'Cycle detected in WorkEffort parent chain'
            break
        }
        current = from('WorkEffort').where(workEffortId: current.workEffortParentId).queryOne()
    }

    return success([valid: errors.isEmpty(), errors: errors])
}
