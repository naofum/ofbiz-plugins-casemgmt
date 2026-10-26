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
import org.apache.ofbiz.base.util.UtilHttp
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.service.ServiceUtil

Locale locale = UtilHttp.getLocale(request)
String caseTypeId = request.getParameter('caseTypeId')?.trim()
if (caseTypeId == null || caseTypeId.isEmpty() || caseTypeId == 'null') {
    request.setAttribute('_ERROR_MESSAGE_',
            UtilProperties.getMessage('CasemgmtUiLabels', 'CasemgmtSelectCaseType', locale))
    return 'error'
}

Map<String, Object> context = [userLogin: userLogin, caseTypeId: caseTypeId]
['customerPartyId', 'ownerPartyId', 'priority', 'channelEnumId', 'description'].each { String name ->
    String value = request.getParameter(name)
    if (value != null && !value.trim().isEmpty() && value != 'null') {
        context[name] = value
    }
}

try {
    Map result = dispatcher.runSync('createCase', context)
    if (ServiceUtil.isError(result)) {
        request.setAttribute('_ERROR_MESSAGE_', ServiceUtil.getErrorMessage(result))
        return 'error'
    }
    request.setAttribute('caseId', result.caseId)
    return 'success'
} catch (Exception e) {
    request.setAttribute('_ERROR_MESSAGE_',
            e.message ?: UtilProperties.getMessage('CasemgmtUiLabels', 'CasemgmtCreateCaseError', locale))
    return 'error'
}
