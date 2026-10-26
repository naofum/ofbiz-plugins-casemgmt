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
import java.net.URLEncoder

import org.apache.ofbiz.service.ServiceUtil

String caseTypeId = request.getParameter('caseTypeId')
boolean valid = false
List<String> errors = []
try {
    Map result = dispatcher.runSync('publishCaseTypeDefinition', [userLogin: userLogin, caseTypeId: caseTypeId])
    if (ServiceUtil.isError(result)) {
        errors = [ServiceUtil.getErrorMessage(result)]
    } else {
        valid = true
    }
} catch (Exception e) {
    errors = [e.message]
}

String target = request.getContextPath() + '/control/EditCaseType?caseTypeId=' +
        URLEncoder.encode(caseTypeId ?: '', 'UTF-8') +
        '&validationRequested=true&validationValid=' + valid +
        '&validationErrors=' + URLEncoder.encode(errors.join('\n'), 'UTF-8')
response.sendRedirect(target)
return 'success'
