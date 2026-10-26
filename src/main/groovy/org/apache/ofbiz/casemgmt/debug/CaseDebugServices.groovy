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
package org.apache.ofbiz.casemgmt.debug

import org.apache.ofbiz.base.util.Debug
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.service.ServiceUtil

/**
 * Debug-only logging services.
 *
 * These services deliberately do not mutate case state. They are intended for
 * service-chain troubleshooting and log the short-lived CaseVariable values
 * associated with a case.
 */

Map logMessage() {
    String message = parameters.message?.toString()
    if (!message) {
        return errorMap('message is required')
    }
    String level = (parameters.level ?: 'INFO').toString().toUpperCase()
    writeLog(level, parameters.loggerName?.toString() ?: 'org.apache.ofbiz.casemgmt.debug.CaseDebugServices', message)
    return successMap([logged: true, level: level])
}

Map logServiceParameters() {
    String caseId = parameters.caseId?.toString()?.trim()
    boolean includeSensitive = ['Y', 'y', 'true', '1'].contains(parameters.includeSensitive?.toString())
    Map inputParameters = parameters.inputParameters instanceof Map ? new LinkedHashMap<>(parameters.inputParameters as Map) : null
    Map outputParameters = parameters.outputParameters instanceof Map ? new LinkedHashMap<>(parameters.outputParameters as Map) : null
    Map extraVariables = parameters.variables instanceof Map ? new LinkedHashMap<>(parameters.variables as Map) : null
    Map<String, Object> caseVariables = [:]
    if (caseId) {
        GenericValue caseValue = EntityQuery.use(delegator).from('Case').where('caseId', caseId).queryOne()
        if (caseValue == null) {
            return errorMap("Case not found: $caseId")
        }
        caseVariables = loadCaseVariables(caseId, includeSensitive)
    }

    StringBuilder text = new StringBuilder('CASE DEBUG service parameters')
    if (parameters.serviceName) {
        text.append('\n service=').append(parameters.serviceName)
    }
    if (inputParameters != null) {
        text.append('\n input=').append(formatMap(inputParameters, includeSensitive))
    }
    if (outputParameters != null) {
        text.append('\n output=').append(formatMap(outputParameters, includeSensitive))
    }
    if (extraVariables != null) {
        text.append('\n variables=').append(formatMap(extraVariables, includeSensitive))
    }
    if (caseId) {
        text.append('\n caseVariables=').append(formatMap(caseVariables, true))
    }
    String level = (parameters.level ?: 'INFO').toString().toUpperCase()
    writeLog(level, parameters.loggerName?.toString() ?: 'org.apache.ofbiz.casemgmt.debug.CaseDebugServices', text.toString())
    return successMap([
            logged       : true,
            level        : level,
            variables    : caseVariables,
            variableCount: caseVariables.size()
    ])
}

Map invokeServiceAndLog() {
    String serviceName = parameters.serviceName?.toString()?.trim()
    if (!serviceName) {
        return errorMap('serviceName is required')
    }
    Map serviceParameters = parameters.serviceParameters instanceof Map ?
            new LinkedHashMap<>(parameters.serviceParameters as Map) : [:]
    serviceParameters.userLogin = userLogin
    boolean includeSensitive = ['Y', 'y', 'true', '1'].contains(parameters.includeSensitive?.toString())
    String caseId = parameters.caseId?.toString()?.trim()

    Map result
    try {
        result = dispatcher.runSync(serviceName, serviceParameters)
    } catch (Exception e) {
        StringBuilder text = new StringBuilder("CASE DEBUG service failed: ${serviceName}")
                .append('\n input=').append(formatMap(serviceParameters, includeSensitive))
                .append('\n error=').append(e.message)
        writeLog('ERROR', 'org.apache.ofbiz.casemgmt.debug.CaseDebugServices', text.toString())
        return errorMap("Debug service invocation failed: ${e.message}")
    }

    Map<String, Object> caseVariables = [:]
    if (caseId) {
        caseVariables = loadCaseVariables(caseId, includeSensitive)
    }
    StringBuilder text = new StringBuilder("CASE DEBUG service=${serviceName}")
            .append('\n input=').append(formatMap(serviceParameters, includeSensitive))
            .append('\n output=').append(formatMap(result, includeSensitive))
    if (caseId) {
        text.append('\n caseVariables=').append(formatMap(caseVariables, true))
    }
    String level = ServiceUtil.isError(result) ? 'ERROR' : (parameters.level ?: 'INFO').toString().toUpperCase()
    writeLog(level, 'org.apache.ofbiz.casemgmt.debug.CaseDebugServices', text.toString())
    return successMap([
            result       : result,
            success      : !ServiceUtil.isError(result),
            variables    : caseVariables,
            variableCount: caseVariables.size()
    ])
}

private Map<String, Object> loadCaseVariables(String caseId, boolean includeSensitive) {
    Map<String, Object> variables = new LinkedHashMap<>()
    EntityQuery.use(delegator)
            .from('CaseVariable')
            .where('caseId', caseId)
            .orderBy('variableName')
            .queryList()
            .each { GenericValue variable ->
                Object value
                switch (variable.valueTypeEnumId) {
                    case 'CASE_VAR_NUMBER':
                        value = variable.valueNumber
                        break
                    case 'CASE_VAR_DATE':
                        value = variable.valueDate
                        break
                    case 'CASE_VAR_BOOLEAN':
                        value = variable.valueBoolean
                        break
                    case 'CASE_VAR_JSON':
                        value = variable.jsonValue
                        break
                    default:
                        value = variable.valueText
                }
                if (variable.sensitiveIndicator == 'Y' && !includeSensitive) {
                    value = '***'
                }
                variables[variable.variableName as String] = value
            }
    return variables
}

private String formatMap(Map map, boolean includeSensitive) {
    if (map == null) {
        return '{}'
    }
    List<Map.Entry> entries = new ArrayList<>(map.entrySet())
    entries.sort { Map.Entry left, Map.Entry right -> left.key.toString() <=> right.key.toString() }
    StringBuilder text = new StringBuilder('{')
    entries.eachWithIndex { Map.Entry entry, int index ->
        String key = entry.key.toString()
        Object value = entry.value
        if (key == 'userLogin' && value instanceof GenericValue) {
            value = value.userLoginId
        }
        if (!includeSensitive && sensitiveKey(key)) {
            value = '***'
        }
        if (index > 0) {
            text.append(', ')
        }
        text.append(key).append('=').append(value)
    }
    return text.append('}').toString()
}

private boolean sensitiveKey(String key) {
    String normalized = key.toLowerCase()
    return normalized.contains('password') || normalized.contains('secret') ||
            normalized.contains('token') || normalized.contains('credential')
}

private void writeLog(String level, String loggerName, String message) {
    switch ((level ?: 'INFO').toUpperCase()) {
        case 'DEBUG':
            Debug.logVerbose(message, loggerName)
            break
        case 'WARN':
        case 'WARNING':
            Debug.logWarning(message, loggerName)
            break
        case 'ERROR':
            Debug.logError(message, loggerName)
            break
        default:
            Debug.logInfo(message, loggerName)
    }
}

private Map successMap(Map values) {
    Map result = ServiceUtil.returnSuccess()
    result.putAll(values)
    return result
}

private Map errorMap(String message) {
    return ServiceUtil.returnError(message)
}
