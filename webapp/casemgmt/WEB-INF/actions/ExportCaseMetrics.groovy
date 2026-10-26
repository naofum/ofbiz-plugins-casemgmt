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
import org.apache.ofbiz.service.ServiceUtil

Map<String, Object> context = [userLogin: userLogin]
['caseTypeId', 'statusId', 'stageSeq', 'queueId', 'assignedPartyId', 'fromDate', 'toDate'].each { String name ->
    String value = request.getParameter(name)
    if (value != null && !value.trim().isEmpty() && value != 'null') {
        context[name] = value
    }
}

Map result = dispatcher.runSync('exportCaseMetrics', context)
if (ServiceUtil.isError(result)) {
    response.setStatus(500)
    response.setContentType('text/plain; charset=UTF-8')
    response.writer.write(ServiceUtil.getErrorMessage(result))
    return 'error'
}

response.setHeader('Content-Disposition', 'attachment; filename="case-metrics.csv"')
response.setContentType('text/csv; charset=UTF-8')
// UTF-8 BOM so spreadsheet applications detect the encoding for Japanese labels.
response.writer.write('\uFEFF')
response.writer.write(result.csv)
return 'success'
