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

import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.Calendar

import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.entity.util.EntityQuery

/**
 * Case metrics and reports.
 *
 * The report intentionally aggregates in memory so the same service works on
 * the supported databases without database-specific SQL. The entity model
 * includes indexes for the date, status and assignment columns used here.
 */
final class CaseReportHelper {

    private static final List<String> OPEN_TASK_STATUSES = [
            'TASK_CREATED', 'TASK_ASSIGNED', 'TASK_IN_PROGRESS', 'TASK_WAITING'
    ]
    private static final List<String> AUTO_TASK_TYPES = [
            'CASE_STEP_SERVICE', 'CASE_STEP_DECISION'
    ]

    private CaseReportHelper() {
    }

    static Map buildMetrics(Delegator delegator, Map filters) {
        Timestamp fromDate = asTimestamp(filters.fromDate)
        Timestamp toDate = asTimestamp(filters.toDate)
        Long stageSeq = asLong(filters.stageSeq)
        String queueId = filters.queueId
        String assignedPartyId = filters.assignedPartyId
        Timestamp now = new Timestamp(System.currentTimeMillis())

        List<GenericValue> allCases = EntityQuery.use(delegator).from('Case').queryList()
        List<String> allCaseIds = allCases.collect { GenericValue value -> value.caseId as String }
        Map<String, GenericValue> casesById = allCases.collectEntries { GenericValue value ->
            [(value.caseId): value]
        }
        Map<String, GenericValue> workEffortsById = allCaseIds.isEmpty() ? [:] :
                EntityQuery.use(delegator)
                        .from('WorkEffort')
                        .where(EntityCondition.makeCondition('workEffortId', EntityOperator.IN, allCaseIds))
                        .queryList()
                        .collectEntries { GenericValue value -> [(value.workEffortId): value] }
        List<GenericValue> allTasks = EntityQuery.use(delegator).from('CaseTask').queryList()
        Map<String, List<GenericValue>> tasksByCase = groupByCase(allTasks)
        List<GenericValue> allStageInstances = EntityQuery.use(delegator).from('CaseStageInstance').queryList()
        Map<String, List<GenericValue>> stagesByCase = groupByCase(allStageInstances)
        List<GenericValue> allSlaInstances = EntityQuery.use(delegator).from('CaseSlaInstance').queryList()
        Map<String, List<GenericValue>> slaByCase = groupByCase(allSlaInstances)
        List<GenericValue> allEvents = EntityQuery.use(delegator).from('CaseEvent').queryList()
        Map<String, List<GenericValue>> eventsByCase = groupByCase(allEvents)
        Map<String, GenericValue> caseTypes = EntityQuery.use(delegator).from('CaseType').queryList()
                .collectEntries { GenericValue value -> [(value.caseTypeId): value] }
        Map<String, GenericValue> stageDefs = EntityQuery.use(delegator).from('CaseStageDef').queryList()
                .collectEntries { GenericValue value ->
                    [(stageKey(value.caseTypeId, value.definitionVersion, value.stageSeq)): value]
                }

        List<GenericValue> selected = []
        Map<String, List<GenericValue>> openTasksByCase = [:]
        for (GenericValue caseValue : allCases) {
            GenericValue workEffort = workEffortsById[caseValue.caseId]
            if (filters.caseTypeId && filters.caseTypeId != caseValue.caseTypeId) {
                continue
            }
            if (filters.statusId && filters.statusId != workEffort?.currentStatusId) {
                continue
            }
            if (fromDate && caseValue.openedDate && caseValue.openedDate.before(fromDate)) {
                continue
            }
            if (toDate && caseValue.openedDate && caseValue.openedDate.after(toDate)) {
                continue
            }
            if (stageSeq != null && asLong(caseValue.currentStageSeq) != stageSeq) {
                continue
            }
            List<GenericValue> tasks = tasksByCase[caseValue.caseId] ?: []
            List<GenericValue> openTasks = tasks.findAll { GenericValue task ->
                OPEN_TASK_STATUSES.contains(task.statusId)
            }
            if (queueId && !openTasks.any { GenericValue task -> task.assignedQueueId == queueId }) {
                continue
            }
            if (assignedPartyId && !openTasks.any { GenericValue task -> task.assignedPartyId == assignedPartyId }) {
                continue
            }
            selected << caseValue
            openTasksByCase[caseValue.caseId] = openTasks
        }

        Set<String> caseIds = selected.collect { GenericValue value -> value.caseId as String } as Set<String>
        int total = selected.size()
        int open = 0
        int resolved = 0
        int closed = 0
        int cancelled = 0
        long totalCycleMinutes = 0L
        int cycleCount = 0
        long totalOpenAgeMinutes = 0L
        int openAgeCount = 0
        int reworkCases = 0
        int autoAdvancedCases = 0
        int escalationCount = 0
        Map<String, Integer> throughputDaily = new TreeMap<>()
        Map<String, Integer> throughputWeekly = new TreeMap<>()
        Map<String, Integer> throughputMonthly = new TreeMap<>()
        Map<String, Map<String, Object>> byTypeAgg = [:]
        Map<String, Map<String, Object>> byStageAgg = [:]
        Map<String, Map<String, Object>> slaMilestoneAgg = [:]
        Map<String, Integer> agingBuckets = new LinkedHashMap<>()
        ['0-1d', '1-3d', '3-7d', '7-14d', '14d+'].each { String bucket -> agingBuckets[bucket] = 0 }

        for (GenericValue caseValue : selected) {
            String status = workEffortsById[caseValue.caseId]?.currentStatusId
            boolean isOpen = !['CASE_RESOLVED', 'CASE_CLOSED', 'CASE_CANCELLED'].contains(status)
            if (status == 'CASE_RESOLVED') {
                resolved++
            } else if (status == 'CASE_CLOSED') {
                closed++
            } else if (status == 'CASE_CANCELLED') {
                cancelled++
            } else {
                open++
            }

            if (caseValue.closedDate && caseValue.openedDate) {
                long minutes = minutesBetween(caseValue.openedDate, caseValue.closedDate)
                totalCycleMinutes += minutes
                cycleCount++
                addThroughput(throughputDaily, throughputWeekly, throughputMonthly, caseValue.closedDate)
            }
            if (isOpen && !caseValue.closedDate && caseValue.openedDate) {
                long minutes = minutesBetween(caseValue.openedDate, now)
                totalOpenAgeMinutes += minutes
                openAgeCount++
                agingBuckets[agingBucket(minutes)] = (agingBuckets[agingBucket(minutes)] ?: 0) + 1
            }

            List<GenericValue> stageInstances = stagesByCase[caseValue.caseId] ?: []
            if (stageInstances.any { GenericValue instance -> (instance.visitSeq ?: 1) > 1 }) {
                reworkCases++
            }
            List<GenericValue> tasks = tasksByCase[caseValue.caseId] ?: []
            if (tasks.any { GenericValue task ->
                AUTO_TASK_TYPES.contains(task.taskTypeEnumId) && task.statusId == 'TASK_COMPLETED'
            }) {
                autoAdvancedCases++
            }
            List<GenericValue> events = eventsByCase[caseValue.caseId] ?: []
            escalationCount += events.count { GenericValue event ->
                event.eventTypeEnumId == 'CASE_SLA_ESCALATED'
            }

            long version = (caseValue.definitionVersion ?: 1) as long
            String typeKey = caseValue.caseTypeId as String
            Map<String, Object> typeRow = byTypeAgg[typeKey]
            if (typeRow == null) {
                typeRow = [
                        caseTypeId         : caseValue.caseTypeId,
                        caseTypeName       : caseTypes[caseValue.caseTypeId]?.caseTypeName ?: caseValue.caseTypeId,
                        total              : 0,
                        open               : 0,
                        resolved           : 0,
                        closed             : 0,
                        cancelled          : 0,
                        totalCycleMinutes  : 0L,
                        cycleCount         : 0
                ]
                byTypeAgg[typeKey] = typeRow
            }
            typeRow.total = (typeRow.total as int) + 1
            if (status == 'CASE_RESOLVED') {
                typeRow.resolved = (typeRow.resolved as int) + 1
            } else if (status == 'CASE_CLOSED') {
                typeRow.closed = (typeRow.closed as int) + 1
            } else if (status == 'CASE_CANCELLED') {
                typeRow.cancelled = (typeRow.cancelled as int) + 1
            } else {
                typeRow.open = (typeRow.open as int) + 1
            }
            if (caseValue.closedDate && caseValue.openedDate) {
                typeRow.totalCycleMinutes = (typeRow.totalCycleMinutes as long) +
                        minutesBetween(caseValue.openedDate, caseValue.closedDate)
                typeRow.cycleCount = (typeRow.cycleCount as int) + 1
            }

            for (GenericValue stageInstance : stageInstances) {
                if (stageInstance.completedDate == null || stageInstance.enteredDate == null) {
                    continue
                }
                String key = stageKey(caseValue.caseTypeId, version, stageInstance.stageSeq)
                Map<String, Object> stageRow = byStageAgg[key]
                if (stageRow == null) {
                    GenericValue stageDef = stageDefs[key]
                    stageRow = [
                            caseTypeId        : caseValue.caseTypeId,
                            definitionVersion : version,
                            stageSeq          : stageInstance.stageSeq,
                            stageName         : stageDef?.stageName ?: "Stage ${stageInstance.stageSeq}",
                            completedCount    : 0,
                            totalCycleMinutes : 0L
                    ]
                    byStageAgg[key] = stageRow
                }
                stageRow.completedCount = (stageRow.completedCount as int) + 1
                stageRow.totalCycleMinutes = (stageRow.totalCycleMinutes as long) +
                        minutesBetween(stageInstance.enteredDate, stageInstance.completedDate)
            }
        }

        for (GenericValue slaInstance : allSlaInstances) {
            if (!caseIds.contains(slaInstance.caseId)) {
                continue
            }
            String key = slaInstance.milestoneName ?: "Milestone ${slaInstance.milestoneSeq}"
            Map<String, Object> row = slaMilestoneAgg[key]
            if (row == null) {
                row = [
                        milestoneName: key,
                        total        : 0,
                        completed    : 0,
                        onTime       : 0,
                        late         : 0,
                        overdue      : 0
                ]
                slaMilestoneAgg[key] = row
            }
            row.total = (row.total as int) + 1
            if (slaInstance.completedDate) {
                row.completed = (row.completed as int) + 1
                if (slaInstance.deadlineDate == null || !slaInstance.completedDate.after(slaInstance.deadlineDate)) {
                    row.onTime = (row.onTime as int) + 1
                } else {
                    row.late = (row.late as int) + 1
                }
            }
            if (['SLA_DEADLINE_MISSED', 'SLA_PASSED_DEADLINE'].contains(slaInstance.slaStateEnumId)) {
                row.overdue = (row.overdue as int) + 1
            }
        }

        int slaTotal = 0
        int slaCompleted = 0
        int slaOnTime = 0
        int slaLate = 0
        int slaOverdue = 0
        int slaAtRisk = 0
        int slaGoalMissed = 0
        for (GenericValue slaInstance : allSlaInstances) {
            if (!caseIds.contains(slaInstance.caseId)) {
                continue
            }
            slaTotal++
            if (slaInstance.completedDate) {
                slaCompleted++
                if (slaInstance.deadlineDate == null || !slaInstance.completedDate.after(slaInstance.deadlineDate)) {
                    slaOnTime++
                } else {
                    slaLate++
                }
            }
            if (['SLA_DEADLINE_MISSED', 'SLA_PASSED_DEADLINE'].contains(slaInstance.slaStateEnumId)) {
                slaOverdue++
            }
            if (slaInstance.slaStateEnumId == 'SLA_AT_RISK') {
                slaAtRisk++
            }
            if (slaInstance.slaStateEnumId == 'SLA_GOAL_MISSED') {
                slaGoalMissed++
            }
        }

        Map<String, Integer> stackByStage = new TreeMap<>()
        Map<String, Integer> stackByQueue = new TreeMap<>()
        Map<String, Integer> stackByAssignee = new TreeMap<>()
        Map<String, Integer> stackBySla = new TreeMap<>()
        int stackTotal = 0
        for (GenericValue caseValue : selected) {
            String status = workEffortsById[caseValue.caseId]?.currentStatusId
            if (['CASE_RESOLVED', 'CASE_CLOSED', 'CASE_CANCELLED'].contains(status)) {
                continue
            }
            stackTotal++
            String stageKey = caseValue.currentStageSeq == null ? 'NONE' : caseValue.currentStageSeq.toString()
            stackByStage[stageKey] = (stackByStage[stageKey] ?: 0) + 1
            String slaKey = caseValue.slaStateEnumId ?: 'NO_SLA'
            stackBySla[slaKey] = (stackBySla[slaKey] ?: 0) + 1
            List<GenericValue> openTasks = openTasksByCase[caseValue.caseId] ?: []
            openTasks.each { GenericValue task ->
                String queueKey = task.assignedQueueId ?: 'UNASSIGNED'
                stackByQueue[queueKey] = (stackByQueue[queueKey] ?: 0) + 1
                String assigneeKey = task.assignedPartyId ?: 'UNASSIGNED'
                stackByAssignee[assigneeKey] = (stackByAssignee[assigneeKey] ?: 0) + 1
            }
        }

        List<Map<String, Object>> byTypeRows = byTypeAgg.values().collect { Map<String, Object> row ->
            int typeCycleCount = row.remove('cycleCount') as int
            long typeTotalCycle = row.remove('totalCycleMinutes') as long
            row.averageCycleMinutes = typeCycleCount == 0 ? 0L : (typeTotalCycle / typeCycleCount)
            row
        }.sort { Map<String, Object> left, Map<String, Object> right ->
            (right.total as int) <=> (left.total as int)
        }
        List<Map<String, Object>> byStageRows = byStageAgg.values().collect { Map<String, Object> row ->
            long totalMinutes = row.remove('totalCycleMinutes') as long
            int count = row.completedCount as int
            row.averageCycleMinutes = count == 0 ? 0L : (totalMinutes / count)
            row
        }.sort { Map<String, Object> left, Map<String, Object> right ->
            (left.caseTypeId as String) <=> (right.caseTypeId as String) ?:
                    ((left.stageSeq as long) <=> (right.stageSeq as long))
        }
        List<Map<String, Object>> slaRows = slaMilestoneAgg.values().collect { Map<String, Object> row ->
            int completed = row.completed as int
            row.compliancePct = completed == 0 ? 0D : roundPct((row.onTime as int), completed)
            row
        }.sort { Map<String, Object> left, Map<String, Object> right ->
            (right.total as int) <=> (left.total as int)
        }

        return [
                summary             : [
                        totalCases           : total,
                        openCases            : open,
                        resolvedCases        : resolved,
                        closedCases          : closed,
                        cancelledCases       : cancelled,
                        averageCycleMinutes  : cycleCount == 0 ? 0L : (totalCycleMinutes / cycleCount),
                        averageOpenAgeMinutes: openAgeCount == 0 ? 0L : (totalOpenAgeMinutes / openAgeCount),
                        slaTotal             : slaTotal,
                        slaCompleted         : slaCompleted,
                        slaOnTime            : slaOnTime,
                        slaLate              : slaLate,
                        slaOverdue           : slaOverdue,
                        slaAtRisk            : slaAtRisk,
                        slaGoalMissed        : slaGoalMissed,
                        slaCompliancePct     : slaCompleted == 0 ? 0D : roundPct(slaOnTime, slaCompleted),
                        reworkCases          : reworkCases,
                        reworkRatePct        : total == 0 ? 0D : roundPct(reworkCases, total),
                        autoAdvancedCases    : autoAdvancedCases,
                        autoAdvanceRatePct   : total == 0 ? 0D : roundPct(autoAdvancedCases, total),
                        escalationCount      : escalationCount,
                        backlogCases         : stackTotal
                ],
                byCaseType          : byTypeRows,
                byStage             : byStageRows,
                slaByMilestone      : slaRows,
                throughputDaily     : mapToSeries('date', throughputDaily),
                throughputWeekly    : mapToSeries('week', throughputWeekly),
                throughputMonthly   : mapToSeries('month', throughputMonthly),
                aging               : agingBuckets.collect { String bucket, Integer count ->
                    [bucket: bucket, count: count]
                },
                stack               : [
                        total     : stackTotal,
                        byStage   : mapToList('stageSeq', stackByStage),
                        byQueue   : mapToList('queueId', stackByQueue),
                        byAssignee: mapToList('partyId', stackByAssignee),
                        bySla     : mapToList('slaStateEnumId', stackBySla)
                ]
        ]
    }

    private static Map<String, List<GenericValue>> groupByCase(List<GenericValue> values) {
        Map<String, List<GenericValue>> grouped = [:]
        values.each { GenericValue value ->
            String caseId = value.caseId as String
            List<GenericValue> list = grouped[caseId]
            if (list == null) {
                list = []
                grouped[caseId] = list
            }
            list << value
        }
        return grouped
    }

    private static void addThroughput(Map<String, Integer> daily,
                                      Map<String, Integer> weekly,
                                      Map<String, Integer> monthly,
                                      Timestamp closedDate) {
        Date date = new Date(closedDate.time)
        String day = new SimpleDateFormat('yyyy-MM-dd').format(date)
        daily[day] = (daily[day] ?: 0) + 1
        String month = new SimpleDateFormat('yyyy-MM').format(date)
        monthly[month] = (monthly[month] ?: 0) + 1
        Calendar calendar = Calendar.getInstance()
        calendar.time = date
        String week = String.format('%04d-W%02d', calendar.get(Calendar.YEAR),
                calendar.get(Calendar.WEEK_OF_YEAR))
        weekly[week] = (weekly[week] ?: 0) + 1
    }

    private static String agingBucket(long minutes) {
        long days = minutes / (60L * 24L)
        if (days < 1) {
            return '0-1d'
        }
        if (days < 3) {
            return '1-3d'
        }
        if (days < 7) {
            return '3-7d'
        }
        if (days < 14) {
            return '7-14d'
        }
        return '14d+'
    }

    private static String stageKey(Object caseTypeId, Object definitionVersion, Object stageSeq) {
        return "${caseTypeId}|${definitionVersion}|${stageSeq}"
    }

    private static long minutesBetween(Timestamp start, Timestamp end) {
        if (start == null || end == null) {
            return 0L
        }
        long minutes = (end.time - start.time).intdiv(60000L) as long
        return minutes < 0L ? 0L : minutes
    }

    private static Long asLong(Object value) {
        if (value == null || value.toString().trim().isEmpty()) {
            return null
        }
        if (value instanceof Number) {
            return ((Number) value).longValue()
        }
        return Long.valueOf(value.toString().trim())
    }

    private static Timestamp asTimestamp(Object value) {
        if (value == null || value.toString().trim().isEmpty()) {
            return null
        }
        if (value instanceof Timestamp) {
            return value
        }
        String text = value.toString().trim().replace('T', ' ')
        if (text.length() == 10) {
            text = text + ' 00:00:00'
        }
        return Timestamp.valueOf(text)
    }

    private static double roundPct(int numerator, int denominator) {
        if (denominator == 0) {
            return 0D
        }
        return Math.round(numerator * 1000D / denominator) / 10D
    }

    private static List<Map<String, Object>> mapToList(String keyName, Map<String, Integer> values) {
        return values.collect { String key, Integer count ->
            [(keyName): key, count: count]
        }.sort { Map<String, Object> left, Map<String, Object> right ->
            (right.count as int) <=> (left.count as int) ?: ((left[keyName] as String) <=> (right[keyName] as String))
        }
    }

    private static List<Map<String, Object>> mapToSeries(String keyName, Map<String, Integer> values) {
        return values.collect { String key, Integer count ->
            [(keyName): key, count: count]
        }.sort { Map<String, Object> left, Map<String, Object> right ->
            (left[keyName] as String) <=> (right[keyName] as String)
        }
    }

    static String toCsv(Map metrics) {
        StringBuilder csv = new StringBuilder()
        appendSection(csv, 'Summary', ['metric', 'value'],
                metrics.summary.collect { String key, Object value -> [key, value] })
        appendSection(csv, 'Cycle time by case type',
                ['caseTypeId', 'caseTypeName', 'total', 'open', 'resolved', 'closed', 'cancelled',
                 'averageCycleMinutes'],
                metrics.byCaseType.collect { Map<String, Object> row ->
                    [row.caseTypeId, row.caseTypeName, row.total, row.open, row.resolved,
                     row.closed, row.cancelled, row.averageCycleMinutes]
                })
        appendSection(csv, 'Cycle time by stage',
                ['caseTypeId', 'definitionVersion', 'stageSeq', 'stageName', 'completedCount',
                 'averageCycleMinutes'],
                metrics.byStage.collect { Map<String, Object> row ->
                    [row.caseTypeId, row.definitionVersion, row.stageSeq, row.stageName,
                     row.completedCount, row.averageCycleMinutes]
                })
        appendSection(csv, 'SLA compliance by milestone',
                ['milestoneName', 'total', 'completed', 'onTime', 'late', 'overdue', 'compliancePct'],
                metrics.slaByMilestone.collect { Map<String, Object> row ->
                    [row.milestoneName, row.total, row.completed, row.onTime, row.late,
                     row.overdue, row.compliancePct]
                })
        appendSection(csv, 'Throughput daily', ['date', 'closedCount'],
                metrics.throughputDaily.collect { Map<String, Object> row -> [row.date, row.count] })
        appendSection(csv, 'Throughput weekly', ['week', 'closedCount'],
                metrics.throughputWeekly.collect { Map<String, Object> row -> [row.week, row.count] })
        appendSection(csv, 'Throughput monthly', ['month', 'closedCount'],
                metrics.throughputMonthly.collect { Map<String, Object> row -> [row.month, row.count] })
        appendSection(csv, 'Aging', ['bucket', 'count'],
                metrics.aging.collect { Map<String, Object> row -> [row.bucket, row.count] })
        appendSection(csv, 'Backlog by stage', ['stageSeq', 'count'],
                metrics.stack.byStage.collect { Map<String, Object> row -> [row.stageSeq, row.count] })
        appendSection(csv, 'Backlog by queue', ['queueId', 'count'],
                metrics.stack.byQueue.collect { Map<String, Object> row -> [row.queueId, row.count] })
        appendSection(csv, 'Backlog by assignee', ['partyId', 'count'],
                metrics.stack.byAssignee.collect { Map<String, Object> row -> [row.partyId, row.count] })
        appendSection(csv, 'Backlog by SLA', ['slaStateEnumId', 'count'],
                metrics.stack.bySla.collect { Map<String, Object> row -> [row.slaStateEnumId, row.count] })
        return csv.toString()
    }

    private static void appendSection(StringBuilder csv, String title, List<String> headers,
                                      List<List<Object>> rows) {
        if (csv.length() > 0) {
            csv.append('\n')
        }
        csv.append(csvLine([title])).append('\n')
        csv.append(csvLine(headers)).append('\n')
        rows.each { List<Object> row ->
            csv.append(csvLine(row)).append('\n')
        }
    }

    private static String csvLine(List<Object> values) {
        return values.collect { Object value -> csvEscape(value) }.join(',')
    }

    private static String csvEscape(Object value) {
        if (value == null) {
            return ''
        }
        String text = value.toString()
        if (text.contains(',') || text.contains('"') || text.contains('\n') || text.contains('\r')) {
            return '"' + text.replace('"', '""') + '"'
        }
        return text
    }
}
