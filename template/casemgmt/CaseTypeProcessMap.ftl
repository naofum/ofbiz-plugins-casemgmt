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
<#if stageMermaid?has_content || stepMermaid?has_content || transitionMermaid?has_content>
    <div class="case-type-process-map">
        <div class="case-type-process-hint">${uiLabelMap.CasemgmtProcessMapDefinitionHint}</div>
        <#if stageMermaid?has_content>
            <div class="case-type-process-section">
                <h4>${uiLabelMap.CasemgmtStages}</h4>
                <div class="mermaid case-type-process-diagram" role="img" aria-label="${uiLabelMap.CasemgmtStages}">
${stageMermaid?replace('__CASE_RESOLVED__', uiLabelMap.CasemgmtCaseResolved)}
                </div>
            </div>
        </#if>
        <#if stepMermaid?has_content>
            <div class="case-type-process-section">
                <h4>${uiLabelMap.CasemgmtSteps}</h4>
                <div class="mermaid case-type-process-diagram" role="img" aria-label="${uiLabelMap.CasemgmtSteps}">
${stepMermaid?replace('__CASE_RESOLVED__', uiLabelMap.CasemgmtCaseResolved)}
                </div>
            </div>
        </#if>
        <#if transitionMermaid?has_content>
            <div class="case-type-process-section">
                <h4>${uiLabelMap.CasemgmtTransitions}</h4>
                <div class="mermaid case-type-process-diagram" role="img" aria-label="${uiLabelMap.CasemgmtTransitions}">
${transitionMermaid?replace('__CASE_RESOLVED__', uiLabelMap.CasemgmtCaseResolved)}
                </div>
            </div>
        </#if>
    </div>
    <script src="${request.contextPath}/js/mermaid.min.js"></script>
    <script>
        (function () {
            if (!window.mermaid) {
                return;
            }
            window.mermaid.initialize({
                startOnLoad: false,
                securityLevel: 'strict',
                theme: 'base',
                flowchart: {
                    htmlLabels: false,
                    curve: 'basis',
                    useMaxWidth: true
                },
                themeVariables: {
                    fontFamily: 'Segoe UI, Arial, sans-serif',
                    fontSize: '13px'
                }
            });
            window.mermaid.run({ querySelector: '.case-type-process-diagram' }).catch(function () {
                // The authoring tables below remain available if rendering fails.
            });
        }());
    </script>
<#elseif processMermaid?has_content>
    <div class="case-type-process-map">
        <div class="case-type-process-hint">${uiLabelMap.CasemgmtProcessMapDefinitionHint}</div>
        <div class="mermaid case-type-process-diagram" role="img" aria-label="${uiLabelMap.CasemgmtProcessMap}">
${processMermaid?replace('__CASE_RESOLVED__', uiLabelMap.CasemgmtCaseResolved)}
        </div>
    </div>
    <script src="${request.contextPath}/js/mermaid.min.js"></script>
    <script>
        (function () {
            if (!window.mermaid) {
                return;
            }
            window.mermaid.initialize({
                startOnLoad: false,
                securityLevel: 'strict',
                theme: 'base',
                flowchart: {
                    htmlLabels: false,
                    curve: 'basis',
                    useMaxWidth: true
                },
                themeVariables: {
                    fontFamily: 'Segoe UI, Arial, sans-serif',
                    fontSize: '13px'
                }
            });
            window.mermaid.run({ querySelector: '.case-type-process-diagram' }).catch(function () {
                // The authoring tables below remain available if rendering fails.
            });
        }());
    </script>
</#if>
<style>
    .case-type-process-map {
        overflow: auto;
        max-height: 70vh;
        border: 1px solid #d9dee3;
        border-radius: 4px;
        padding: 10px;
        margin-bottom: 14px;
        background: #fff;
    }
    .case-type-process-section {
        margin-bottom: 18px;
    }
    .case-type-process-section h4 {
        margin: 8px 0;
        font-size: 14px;
    }
    .case-type-process-diagram {
        min-width: 640px;
    }
    .case-type-process-hint {
        color: #6c757d;
        font-size: 12px;
        margin-bottom: 8px;
    }
</style>
