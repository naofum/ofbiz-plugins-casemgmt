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

import java.sql.Time
import java.sql.Timestamp
import java.util.Calendar

import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.util.EntityQuery

/**
 * Adds SLA durations using an OFBiz TechDataCalendar business calendar.
 *
 * <p>This is intentionally a small local helper rather than a dependency on
 * the manufacturing application. Exception days are supported; exception
 * weeks are not yet handled and fall back to the base week.</p>
 */
final class BusinessCalendarHelper {

    private BusinessCalendarHelper() {
    }

    static Timestamp addMinutes(Delegator delegator, String calendarId, Timestamp start, long minutes) {
        Timestamp effectiveStart = start ?: new Timestamp(System.currentTimeMillis())
        if (!calendarId || minutes <= 0) {
            return new Timestamp(effectiveStart.time + minutes * 60000L)
        }

        GenericValue calendar = EntityQuery.use(delegator)
                .from('TechDataCalendar')
                .where('calendarId', calendarId)
                .queryOne()
        if (calendar == null || !calendar.calendarWeekId) {
            return new Timestamp(effectiveStart.time + minutes * 60000L)
        }
        GenericValue week = EntityQuery.use(delegator)
                .from('TechDataCalendarWeek')
                .where('calendarWeekId', calendar.calendarWeekId)
                .queryOne()
        if (week == null) {
            return new Timestamp(effectiveStart.time + minutes * 60000L)
        }

        long remaining = minutes
        Calendar cal = Calendar.getInstance()
        cal.setTimeInMillis(effectiveStart.time)
        int guard = 0
        while (remaining > 0 && guard++ < 3660) {
            Map<String, Object> window = getDayWindow(delegator, calendarId, week, cal)
            if (window == null || (window.capacity as double) <= 0D) {
                moveToStartOfNextDay(cal)
                continue
            }

            Time startTime = window.startTime as Time
            double capacityHours = window.capacity as double
            Calendar windowStart = Calendar.getInstance()
            windowStart.setTimeInMillis(cal.timeInMillis)
            windowStart.set(Calendar.HOUR_OF_DAY, startTime.hours)
            windowStart.set(Calendar.MINUTE, startTime.minutes)
            windowStart.set(Calendar.SECOND, startTime.seconds)
            windowStart.set(Calendar.MILLISECOND, 0)

            Calendar windowEnd = Calendar.getInstance()
            windowEnd.setTimeInMillis(windowStart.timeInMillis + (long) (capacityHours * 60L * 60000L))

            if (cal.before(windowStart)) {
                cal.setTimeInMillis(windowStart.timeInMillis)
            }
            if (!cal.before(windowEnd)) {
                moveToStartOfNextDay(cal)
                continue
            }

            long available = (windowEnd.timeInMillis - cal.timeInMillis) / 60000L
            if (remaining <= available) {
                cal.add(Calendar.MINUTE, (int) remaining)
                remaining = 0
            } else {
                remaining -= available
                moveToStartOfNextDay(cal)
            }
        }
        return new Timestamp(cal.timeInMillis)
    }

    private static Map<String, Object> getDayWindow(Delegator delegator, String calendarId,
                                                    GenericValue week, Calendar day) {
        Calendar dayStart = Calendar.getInstance()
        dayStart.setTimeInMillis(day.timeInMillis)
        dayStart.set(Calendar.HOUR_OF_DAY, 0)
        dayStart.set(Calendar.MINUTE, 0)
        dayStart.set(Calendar.SECOND, 0)
        dayStart.set(Calendar.MILLISECOND, 0)
        Calendar nextDay = Calendar.getInstance()
        nextDay.setTimeInMillis(dayStart.timeInMillis + 24L * 60L * 60L * 1000L)

        List<GenericValue> exceptionDays = EntityQuery.use(delegator)
                .from('TechDataCalendarExcDay')
                .where('calendarId', calendarId)
                .queryList()
        GenericValue exceptionDay = exceptionDays.find { GenericValue exception ->
            Timestamp exceptionStart = exception.exceptionDateStartTime as Timestamp
            exceptionStart != null &&
                    exceptionStart.time >= dayStart.timeInMillis &&
                    exceptionStart.time < nextDay.timeInMillis
        }
        if (exceptionDay != null) {
            return [
                    startTime: exceptionDay.exceptionDateStartTime,
                    capacity : exceptionDay.exceptionCapacity ?: 0D
            ]
        }

        String prefix = dayPrefix(day.get(Calendar.DAY_OF_WEEK))
        if (!prefix) {
            return null
        }
        Time startTime = week.get("${prefix}StartTime") as Time
        Object capacity = week.get("${prefix}Capacity")
        if (startTime == null || capacity == null) {
            return null
        }
        return [startTime: startTime, capacity: capacity]
    }

    private static String dayPrefix(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.MONDAY:
                return 'monday'
            case Calendar.TUESDAY:
                return 'tuesday'
            case Calendar.WEDNESDAY:
                return 'wednesday'
            case Calendar.THURSDAY:
                return 'thursday'
            case Calendar.FRIDAY:
                return 'friday'
            case Calendar.SATURDAY:
                return 'saturday'
            case Calendar.SUNDAY:
                return 'sunday'
            default:
                return null
        }
    }

    private static void moveToStartOfNextDay(Calendar cal) {
        cal.add(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }
}
