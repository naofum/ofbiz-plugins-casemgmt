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
package org.apache.ofbiz.casemgmt.visibility

import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.util.EntityQuery

/**
 * Process map, stack analysis and flow simulation helpers.
 */
final class CaseProcessHelper {

    private CaseProcessHelper() {
    }

    static Map buildProcessMap(Delegator delegator, String caseId) {
        GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', caseId).queryOne()
        if (caseValue == null) {
            return [:]
        }
        GenericValue workEffort = EntityQuery.use(delegator)
                .from('WorkEffort')
                .where('workEffortId', caseId)
                .queryOne()
        long version = (caseValue.definitionVersion ?: 1) as long
        List<GenericValue> stages = EntityQuery.use(delegator)
                .from('CaseStageDef')
                .where(EntityCondition.makeCondition([
                        caseTypeId: caseValue.caseTypeId, definitionVersion: version
                ]))
                .orderBy('stageSeq')
                .queryList()
        List<GenericValue> steps = EntityQuery.use(delegator)
                .from('CaseStepDef')
                .where(EntityCondition.makeCondition([
                        caseTypeId: caseValue.caseTypeId, definitionVersion: version
                ]))
                .orderBy('stageSeq')
                .queryList()
        List<GenericValue> transitions = EntityQuery.use(delegator)
                .from('CaseStepTransition')
                .where(EntityCondition.makeCondition([
                        caseTypeId: caseValue.caseTypeId, definitionVersion: version
                ]))
                .orderBy('stageSeq')
                .queryList()
        List<GenericValue> stageInstances = EntityQuery.use(delegator)
                .from('CaseStageInstance')
                .where('caseId', caseId)
                .queryList()
        List<GenericValue> tasks = EntityQuery.use(delegator)
                .from('CaseTask')
                .where('caseId', caseId)
                .queryList()
        List<GenericValue> slaInstances = EntityQuery.use(delegator)
                .from('CaseSlaInstance')
                .where('caseId', caseId)
                .queryList()

        List<Map<String, Object>> stageRows = []
        List<Map<String, Object>> stepRows = []
        stages.each { GenericValue stage ->
            List<GenericValue> instances = stageInstances.findAll { GenericValue instance ->
                instance.stageSeq == stage.stageSeq
            }.sort { GenericValue left, GenericValue right ->
                (left.visitSeq ?: 0) <=> (right.visitSeq ?: 0)
            }
            GenericValue latestInstance = instances.isEmpty() ? null : instances.last()
            boolean current = caseValue.currentStageSeq == stage.stageSeq
            String stageStatus = current ? 'CURRENT' :
                    (latestInstance?.statusId == 'CASE_STAGE_COMPLETED' ? 'COMPLETED' : 'FUTURE')
            List<GenericValue> stageSteps = steps.findAll { GenericValue step -> step.stageSeq == stage.stageSeq }
            int completedSteps = 0
            stageSteps.each { GenericValue step ->
                List<GenericValue> stepTasks = tasks.findAll { GenericValue task ->
                    task.stageSeq == stage.stageSeq && task.stepSeq == step.stepSeq
                }.sort { GenericValue left, GenericValue right ->
                    (left.caseTaskId ?: '') <=> (right.caseTaskId ?: '')
                }
                GenericValue latestTask = stepTasks.isEmpty() ? null : stepTasks.last()
                if (latestTask?.statusId == 'TASK_COMPLETED') {
                    completedSteps++
                }
                stepRows << [
                        stageSeq       : stage.stageSeq,
                        stageName      : stage.stageName,
                        stepSeq        : step.stepSeq,
                        stepName       : step.stepName,
                        stepTypeEnumId : step.stepTypeEnumId,
                        requiredIndicator: step.requiredIndicator,
                        statusId       : latestTask?.statusId ?: 'NOT_STARTED',
                        assignedPartyId: latestTask?.assignedPartyId,
                        assignedQueueId: latestTask?.assignedQueueId,
                        caseTaskId     : latestTask?.caseTaskId,
                        current        : current && caseValue.currentStepSeq == step.stepSeq
                ]
            }
            stageRows << [
                    stageSeq      : stage.stageSeq,
                    stageName     : stage.stageName,
                    statusId      : stageStatus,
                    visitSeq      : latestInstance?.visitSeq,
                    stepCount     : stageSteps.size(),
                    completedSteps: completedSteps,
                    current       : current
            ]
        }
        List<Map<String, Object>> transitionRows = transitions.collect { GenericValue transition ->
            [
                    stageSeq            : transition.stageSeq,
                    stepSeq             : transition.stepSeq,
                    outcomeEnumId       : transition.outcomeEnumId,
                    transitionSeq       : transition.transitionSeq,
                    toStageSeq          : transition.toStageSeq,
                    toStepSeq           : transition.toStepSeq,
                    conditionServiceName: transition.conditionServiceName,
                    priority            : transition.priority,
                    isDefault           : transition.isDefault
            ]
        }
        List<Map<String, Object>> slaRows = slaInstances.collect { GenericValue instance ->
            [
                    milestoneSeq      : instance.milestoneSeq,
                    milestoneName     : instance.milestoneName,
                    slaStateEnumId    : instance.slaStateEnumId,
                    goalDate          : instance.goalDate,
                    deadlineDate      : instance.deadlineDate,
                    passedDeadlineDate: instance.passedDeadlineDate,
                    completedDate     : instance.completedDate
            ]
        }
        return [
                caseId              : caseId,
                caseTypeId          : caseValue.caseTypeId,
                caseNumber          : caseValue.caseNumber,
                statusId            : workEffort?.currentStatusId,
                definitionVersion   : version,
                currentStageSeq     : caseValue.currentStageSeq,
                currentStageVisitSeq: caseValue.currentStageVisitSeq,
                currentStepSeq      : caseValue.currentStepSeq,
                stages              : stageRows,
                steps               : stepRows,
                transitions         : transitionRows,
                sla                 : slaRows,
                mermaidDefinition   : buildMermaidDefinition(stageRows, stepRows, transitionRows)
        ]
    }

    /**
     * Build a definition-only process map for case type authoring. Unlike the
     * case instance map there is no current/completed state to highlight.
     */
    static Map buildCaseTypeProcessMap(Delegator delegator, String caseTypeId, Long definitionVersion = null) {
        GenericValue caseType = EntityQuery.use(delegator)
                .from('CaseType')
                .where('caseTypeId', caseTypeId)
                .queryOne()
        if (caseType == null) {
            return [error: "Case type not found: $caseTypeId"]
        }
        long version = (definitionVersion ?: caseType.definitionVersion ?: caseType.activeDefinitionVersion ?: 1) as long
        List<GenericValue> stageDefs = EntityQuery.use(delegator)
                .from('CaseStageDef')
                .where(EntityCondition.makeCondition([caseTypeId: caseTypeId, definitionVersion: version]))
                .orderBy('stageSeq')
                .queryList()
        List<GenericValue> stepDefs = EntityQuery.use(delegator)
                .from('CaseStepDef')
                .where(EntityCondition.makeCondition([caseTypeId: caseTypeId, definitionVersion: version]))
                .orderBy('stageSeq', 'stepSeq')
                .queryList()
        List<GenericValue> transitionDefs = EntityQuery.use(delegator)
                .from('CaseStepTransition')
                .where(EntityCondition.makeCondition([caseTypeId: caseTypeId, definitionVersion: version]))
                .orderBy('stageSeq', 'stepSeq', 'outcomeEnumId', 'transitionSeq')
                .queryList()

        List<Map<String, Object>> stageRows = stageDefs.collect { GenericValue stage ->
            [
                    caseTypeId      : caseTypeId,
                    definitionVersion: version,
                    stageSeq        : stage.stageSeq,
                    stageName       : stage.stageName,
                    stageTypeEnumId : stage.stageTypeEnumId,
                    autoAdvance     : stage.autoAdvance,
                    statusId        : null,
                    visitSeq        : null,
                    stepCount       : stepDefs.count { GenericValue step -> step.stageSeq == stage.stageSeq },
                    completedSteps  : 0
            ]
        }
        List<Map<String, Object>> stepRows = stepDefs.collect { GenericValue step ->
            [
                    caseTypeId       : caseTypeId,
                    definitionVersion: version,
                    stageSeq         : step.stageSeq,
                    stepSeq          : step.stepSeq,
                    stepName         : step.stepName,
                    stepTypeEnumId   : step.stepTypeEnumId,
                    assignmentPolicyId: step.assignmentPolicyId,
                    statusId         : null,
                    current          : false,
                    assignedPartyId  : null,
                    assignedQueueId  : null
            ]
        }
        List<Map<String, Object>> transitionRows = transitionDefs.collect { GenericValue transition ->
            [
                    caseTypeId          : caseTypeId,
                    definitionVersion   : version,
                    stageSeq            : transition.stageSeq,
                    stepSeq             : transition.stepSeq,
                    outcomeEnumId       : transition.outcomeEnumId,
                    transitionSeq       : transition.transitionSeq,
                    toStageSeq          : transition.toStageSeq,
                    toStepSeq           : transition.toStepSeq,
                    conditionServiceName: transition.conditionServiceName,
                    priority            : transition.priority,
                    isDefault           : transition.isDefault
            ]
        }
        return [
                caseTypeId            : caseTypeId,
                resolvedDefinitionVersion: version,
                stages                : stageRows,
                steps                 : stepRows,
                transitions           : transitionRows,
                stageMermaidDefinition: buildStageMermaidDefinition(stageRows),
                stepMermaidDefinition : buildStepMermaidDefinition(stageRows, stepRows),
                transitionMermaidDefinition: buildMermaidDefinition(stageRows, stepRows, transitionRows, false),
                mermaidDefinition     : buildMermaidDefinition(stageRows, stepRows, transitionRows, false)
        ]
    }

    private static String buildStageMermaidDefinition(List<Map<String, Object>> stages) {
        if (stages.isEmpty()) {
            return ''
        }
        StringBuilder graph = new StringBuilder('flowchart LR\n')
        graph.append('  classDef stage fill:#f8f9fa,stroke:#adb5bd,color:#000;\n')
        stages.eachWithIndex { Map<String, Object> stage, int index ->
            String stageId = "stage_${stage.stageSeq}"
            graph.append("  ${stageId}[\"${mermaidLabel('Stage ' + stage.stageSeq + ': ' + stage.stageName)}\"]\n")
            if (index > 0) {
                Map<String, Object> previous = stages[index - 1]
                graph.append("  stage_${previous.stageSeq} --> ${stageId}\n")
            }
        }
        graph.append("  class ${stages.collect { Map<String, Object> stage -> 'stage_' + stage.stageSeq }.join(',')} stage\n")
        return graph.toString()
    }

    private static String buildStepMermaidDefinition(List<Map<String, Object>> stages,
                                                     List<Map<String, Object>> steps) {
        if (stages.isEmpty()) {
            return ''
        }
        StringBuilder graph = new StringBuilder('flowchart LR\n')
        graph.append('  classDef step fill:#f8f9fa,stroke:#adb5bd,color:#000;\n')
        stages.each { Map<String, Object> stage ->
            String stageId = "stage_${stage.stageSeq}"
            graph.append("  subgraph ${stageId}[\"${mermaidLabel('Stage ' + stage.stageSeq + ': ' + stage.stageName)}\"]\n")
            graph.append('    direction TB\n')
            List<Map<String, Object>> stageSteps = steps.findAll { Map<String, Object> step ->
                step.stageSeq == stage.stageSeq
            }
            if (stageSteps.isEmpty()) {
                graph.append("    auto_${stage.stageSeq}[\"Auto advance\"]\n")
            } else {
                stageSteps.each { Map<String, Object> step ->
                    String nodeId = stepNodeId(step)
                    graph.append("    ${nodeId}[\"${mermaidLabel('Step ' + step.stepSeq + ': ' + step.stepName)}\"]\n")
                }
                for (int i = 0; i < stageSteps.size() - 1; i++) {
                    graph.append("    ${stepNodeId(stageSteps[i])} --> ${stepNodeId(stageSteps[i + 1])}\n")
                }
            }
            graph.append('  end\n')
            graph.append("  style ${stageId} fill:#ffffff,stroke:#ced4da,stroke-width:1px\n")
        }
        return graph.toString()
    }

    /**
     * Build a read-only Mermaid flowchart from the same metadata used by the
     * process map tables. Node ids intentionally exclude user data.
     */
    private static String buildMermaidDefinition(List<Map<String, Object>> stages,
                                                  List<Map<String, Object>> steps,
                                                  List<Map<String, Object>> transitions,
                                                  boolean includeStatus = true) {
        if (stages.isEmpty()) {
            return ''
        }
        StringBuilder graph = new StringBuilder('flowchart LR\n')
        graph.append('  classDef current fill:#fff3cd,stroke:#d39e00,stroke-width:2px,color:#000;\n')
        graph.append('  classDef completed fill:#d4edda,stroke:#2e7d32,color:#000;\n')
        graph.append('  classDef future fill:#f8f9fa,stroke:#adb5bd,color:#000;\n')
        graph.append('  classDef definition fill:#f8f9fa,stroke:#adb5bd,color:#000;\n')
        graph.append('  classDef terminal fill:#e2e3e5,stroke:#6c757d,color:#000;\n')

        Set<String> terminalNodes = new LinkedHashSet<>()
        stages.each { Map<String, Object> stage ->
            String stageId = "stage_${stage.stageSeq}"
            graph.append("  subgraph ${stageId}[\"${mermaidLabel('Stage ' + stage.stageSeq + ': ' + stage.stageName)}\"]\n")
            graph.append('    direction TB\n')
            List<Map<String, Object>> stageSteps = steps.findAll { Map<String, Object> step ->
                step.stageSeq == stage.stageSeq
            }
            if (stageSteps.isEmpty()) {
                String autoId = "auto_${stage.stageSeq}"
                graph.append("    ${autoId}[\"Auto advance\"]\n")
                terminalNodes.add(autoId)
            } else {
                stageSteps.each { Map<String, Object> step ->
                    String nodeId = stepNodeId(step)
                    String statusSuffix = includeStatus && step.statusId ? ' - ' + step.statusId : ''
                    graph.append("    ${nodeId}[\"${mermaidLabel('Step ' + step.stepSeq + ': ' + step.stepName + statusSuffix)}\"]\n")
                }
            }
            graph.append('  end\n')
            graph.append("  style ${stageId} fill:${includeStatus ? stageColor(stage.statusId) : '#ffffff'},stroke:${includeStatus ? stageStroke(stage.statusId) : '#ced4da'},stroke-width:1px\n")
        }

        Set<String> currentNodeIds = new LinkedHashSet<>()
        Set<String> completedNodeIds = new LinkedHashSet<>()
        Set<String> futureNodeIds = new LinkedHashSet<>()
        Set<String> definitionNodeIds = new LinkedHashSet<>()
        steps.each { Map<String, Object> step ->
            String nodeId = stepNodeId(step)
            if (includeStatus) {
                if (step.current) {
                    currentNodeIds.add(nodeId)
                } else if (step.statusId == 'TASK_COMPLETED') {
                    completedNodeIds.add(nodeId)
                } else {
                    futureNodeIds.add(nodeId)
                }
            } else {
                definitionNodeIds.add(nodeId)
            }
            boolean hasOutgoing = transitions.any { Map<String, Object> transition ->
                transition.stageSeq == step.stageSeq && transition.stepSeq == step.stepSeq
            }
            if (!hasOutgoing) {
                terminalNodes.add(nodeId)
            }
        }
        if (!currentNodeIds.isEmpty()) {
            graph.append("  class ${currentNodeIds.join(',')} current\n")
        }
        if (!completedNodeIds.isEmpty()) {
            graph.append("  class ${completedNodeIds.join(',')} completed\n")
        }
        if (!futureNodeIds.isEmpty()) {
            graph.append("  class ${futureNodeIds.join(',')} future\n")
        }
        if (!definitionNodeIds.isEmpty()) {
            graph.append("  class ${definitionNodeIds.join(',')} definition\n")
        }

        boolean endDefined = false
        transitions.each { Map<String, Object> transition ->
            String fromNode = stepNodeId(transition)
            boolean hasTarget = transition.toStageSeq != null && (transition.toStageSeq as long) > 0 &&
                    transition.toStepSeq != null && (transition.toStepSeq as long) > 0
            String toNode = hasTarget ? "step_${transition.toStageSeq}_${transition.toStepSeq}" : 'CASE_END'
            if (!hasTarget && !endDefined) {
                graph.append('  CASE_END(["__CASE_RESOLVED__"])\n')
                endDefined = true
            }
            String label = mermaidLabel(outcomeLabel(transition.outcomeEnumId) +
                    (transition.isDefault == 'Y' ? ' (default)' : '') +
                    (transition.conditionServiceName ? ' / ' + transition.conditionServiceName : ''))
            graph.append("  ${fromNode} -->|\"${label}\"| ${toNode}\n")
        }

        if (!terminalNodes.isEmpty()) {
            if (!endDefined) {
                graph.append('  CASE_END(["__CASE_RESOLVED__"])\n')
                endDefined = true
            }
            terminalNodes.each { String nodeId ->
                graph.append("  ${nodeId} --> CASE_END\n")
            }
        }
        if (endDefined) {
            graph.append('  class CASE_END terminal\n')
        }
        return graph.toString()
    }

    private static String stepNodeId(Map<String, Object> step) {
        return "step_${step.stageSeq}_${step.stepSeq}"
    }

    private static String outcomeLabel(Object outcome) {
        String text = outcome?.toString() ?: 'COMPLETE'
        return text.replace('CASE_OUT_', '')
    }

    private static String stageColor(Object status) {
        switch (status?.toString()) {
            case 'CURRENT':
                return '#fff8e1'
            case 'COMPLETED':
                return '#e8f5e9'
            default:
                return '#ffffff'
        }
    }

    private static String stageStroke(Object status) {
        switch (status?.toString()) {
            case 'CURRENT':
                return '#f0ad4e'
            case 'COMPLETED':
                return '#81c784'
            default:
                return '#ced4da'
        }
    }

    private static String mermaidLabel(Object value) {
        String text = value?.toString() ?: ''
        return text.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('"', "'")
                .replace('|', '/')
                .replace('[', '(')
                .replace(']', ')')
                .replace('{', '(')
                .replace('}', ')')
                .replace('<', '')
                .replace('>', '')
                .replace('&', 'and')
                .replace(';', ',')
                .replace('#', '')
                .replace('`', '')
    }

    static Map buildStack(Delegator delegator, Map filters) {
        List<GenericValue> cases = EntityQuery.use(delegator).from('Case').queryList()
        Map<String, Integer> byStage = new TreeMap<>()
        Map<String, Integer> byQueue = new TreeMap<>()
        Map<String, Integer> byAssignee = new TreeMap<>()
        Map<String, Integer> bySla = new TreeMap<>()
        int total = 0
        for (GenericValue caseValue : cases) {
            if (filters.caseTypeId && filters.caseTypeId != caseValue.caseTypeId) {
                continue
            }
            List<GenericValue> tasks = EntityQuery.use(delegator)
                    .from('CaseTask')
                    .where('caseId', caseValue.caseId)
                    .queryList()
                    .findAll { GenericValue task ->
                        ['TASK_CREATED', 'TASK_ASSIGNED', 'TASK_IN_PROGRESS', 'TASK_WAITING'].contains(task.statusId)
                    }
            if (filters.stageSeq != null && caseValue.currentStageSeq != filters.stageSeq) {
                continue
            }
            if (filters.queueId && !tasks.any { GenericValue task -> task.assignedQueueId == filters.queueId }) {
                continue
            }
            if (filters.assignedPartyId && !tasks.any { GenericValue task -> task.assignedPartyId == filters.assignedPartyId }) {
                continue
            }
            total++
            String stageKey = caseValue.currentStageSeq == null ? 'NONE' : caseValue.currentStageSeq.toString()
            byStage[stageKey] = (byStage[stageKey] ?: 0) + 1
            String slaKey = caseValue.slaStateEnumId ?: 'NO_SLA'
            bySla[slaKey] = (bySla[slaKey] ?: 0) + 1
            tasks.each { GenericValue task ->
                String queueKey = task.assignedQueueId ?: 'UNASSIGNED'
                byQueue[queueKey] = (byQueue[queueKey] ?: 0) + 1
                String assigneeKey = task.assignedPartyId ?: 'UNASSIGNED'
                byAssignee[assigneeKey] = (byAssignee[assigneeKey] ?: 0) + 1
            }
        }
        return [
                total      : total,
                byStage    : mapToList('stageSeq', byStage),
                byQueue    : mapToList('queueId', byQueue),
                byAssignee : mapToList('partyId', byAssignee),
                bySla      : mapToList('slaStateEnumId', bySla)
        ]
    }

    static Map simulate(Delegator delegator, String caseTypeId, Long definitionVersion,
                        String outcomeEnumId, int maxSteps) {
        GenericValue caseType = EntityQuery.use(delegator).from('CaseType').where('caseTypeId', caseTypeId).queryOne()
        if (caseType == null) {
            return [error: "Case type not found: $caseTypeId"]
        }
        long version = (definitionVersion ?: caseType.activeDefinitionVersion ?: caseType.definitionVersion ?: 1) as long
        List<GenericValue> stages = EntityQuery.use(delegator)
                .from('CaseStageDef')
                .where(EntityCondition.makeCondition([caseTypeId: caseTypeId, definitionVersion: version]))
                .orderBy('stageSeq')
                .queryList()
        List<GenericValue> steps = EntityQuery.use(delegator)
                .from('CaseStepDef')
                .where(EntityCondition.makeCondition([caseTypeId: caseTypeId, definitionVersion: version]))
                .orderBy('stageSeq')
                .queryList()
        List<GenericValue> transitions = EntityQuery.use(delegator)
                .from('CaseStepTransition')
                .where(EntityCondition.makeCondition([caseTypeId: caseTypeId, definitionVersion: version]))
                .queryList()
        if (stages.isEmpty()) {
            return [error: 'No stages defined', definitionVersion: version]
        }
        String outcome = outcomeEnumId ?: 'CASE_OUT_COMPLETE'
        List<Map<String, Object>> path = []
        List<String> warnings = []
        long currentStage = stages[0].stageSeq as long
        int guard = 0
        while (guard++ < Math.max(1, maxSteps)) {
            GenericValue stage = stages.find { GenericValue value -> value.stageSeq == currentStage }
            if (stage == null) {
                warnings << "Stage not found: $currentStage"
                break
            }
            List<GenericValue> stageSteps = steps.findAll { GenericValue step -> step.stageSeq == currentStage }
            GenericValue step = stageSteps.isEmpty() ? null : stageSteps[0]
            long stepSeq = step == null ? 0L : step.stepSeq as long
            path << [stageSeq: currentStage, stageName: stage.stageName,
                     stepSeq: stepSeq, stepName: step?.stepName, outcomeEnumId: outcome]
            GenericValue transition = transitions.find { GenericValue value ->
                value.stageSeq == currentStage && (step == null || value.stepSeq == stepSeq) &&
                        value.outcomeEnumId == outcome
            }
            Long nextStage = null
            if (transition?.toStageSeq != null && (transition.toStageSeq as long) > 0) {
                nextStage = transition.toStageSeq as long
            } else {
                GenericValue next = stages.find { GenericValue value -> (value.stageSeq as long) > currentStage }
                nextStage = next == null ? null : next.stageSeq as long
            }
            if (nextStage == null) {
                return [path: path, resolved: true, warnings: warnings, definitionVersion: version]
            }
            if (nextStage == currentStage) {
                warnings << "Self transition at stage $currentStage"
                break
            }
            currentStage = nextStage
        }
        warnings << "Simulation exceeded $maxSteps steps"
        return [path: path, resolved: false, warnings: warnings, definitionVersion: version]
    }

    private static List<Map<String, Object>> mapToList(String keyName, Map<String, Integer> values) {
        return values.collect { String key, Integer count ->
            [(keyName): key, count: count]
        }.sort { Map<String, Object> left, Map<String, Object> right ->
            (right.count as int) <=> (left.count as int)
        }
    }
}
