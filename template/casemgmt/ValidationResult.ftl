<#--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
<#assign validationRequested = requestParameters.validationRequested!>
<#if validationRequested?has_content && validationRequested == 'true'>
    <#assign validationValid = requestParameters.validationValid!>
    <#assign validationErrors = requestParameters.validationErrors!>
    <div class="screenlet">
        <div class="screenlet-title-bar"><ul><li class="h3">${uiLabelMap.CasemgmtValidationResult}</li></ul><br class="clear"/></div>
        <div class="screenlet-body">
            <#if validationValid == 'true'>
                <div class="alert alert-success">${uiLabelMap.CasemgmtValidationSuccess}</div>
            <#else>
                <div class="alert alert-danger">${uiLabelMap.CasemgmtValidationFailed}</div>
                <ul>
                    <#list validationErrors?split("\n") as error>
                        <#if error?has_content><li>${error}</li></#if>
                    </#list>
                </ul>
            </#if>
        </div>
    </div>
</#if>
