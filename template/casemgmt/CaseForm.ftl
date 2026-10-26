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
<form method="post" action="<@ofbizUrl>SaveCaseForm</@ofbizUrl>" class="basic-form">
    <input type="hidden" name="caseId" value="${caseId?default('')}"/>
    <input type="hidden" name="stageSeq" value="${stageSeq?default('')}"/>
    <input type="hidden" name="stepSeq" value="${stepSeq?default('')}"/>
    <#list fields as field>
        <#if field.visible>
            <div class="form-row">
                <label for="field_${field.fieldName}">${field.label?default(field.fieldName)}<#if field.requiredIndicator == 'Y'> *</#if></label>
                <#if field.fieldTypeEnumId == 'CASE_FIELD_TEXTAREA'>
                    <textarea id="field_${field.fieldName}" name="field_${field.fieldName}"<#if field.readOnly> readonly="readonly"</#if>>${field.value?default('')}</textarea>
                <#elseif field.fieldTypeEnumId == 'CASE_FIELD_CHECKBOX'>
                    <input type="checkbox" id="field_${field.fieldName}" name="field_${field.fieldName}" value="Y"<#if (field.value?default('')) == 'Y'> checked="checked"</#if><#if field.readOnly> disabled="disabled"</#if>/>
                <#elseif field.fieldTypeEnumId == 'CASE_FIELD_DROPDOWN' || field.fieldTypeEnumId == 'CASE_FIELD_LOOKUP'>
                    <select id="field_${field.fieldName}" name="field_${field.fieldName}"<#if field.readOnly> disabled="disabled"</#if>>
                        <option value=""></option>
                        <#list field.options as option>
                            <option value="${option.value?default('')}"<#if (option.value?default('')) == (field.value?default(''))> selected="selected"</#if>>${option.label?default(option.value?default(''))}</option>
                        </#list>
                    </select>
                <#else>
                    <input type="text" id="field_${field.fieldName}" name="field_${field.fieldName}" value="${field.value?default('')}"<#if field.readOnly> readonly="readonly"</#if>/>
                </#if>
            </div>
        </#if>
    </#list>
    <div class="form-row">
        <button type="submit" class="buttontext">${uiLabelMap.CasemgmtSave?default('Save')}</button>
    </div>
</form>
