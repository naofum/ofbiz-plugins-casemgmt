<#--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  You may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
<#if processMermaid?has_content>
    <div class="case-process-view-switch" role="group" aria-label="${uiLabelMap.CasemgmtProcessMap}">
        <button type="button" class="case-process-view-button is-active" data-case-view="diagram"
                aria-pressed="true">${uiLabelMap.CasemgmtProcessMapGraph}</button>
        <button type="button" class="case-process-view-button" data-case-view="table"
                aria-pressed="false">${uiLabelMap.CasemgmtProcessMapTable}</button>
    </div>
    <div class="case-process-map">
        <div class="case-process-legend">
            <span class="case-process-legend-current">${uiLabelMap.CasemgmtCurrent}</span>
            <span class="case-process-legend-completed">${uiLabelMap.CasemgmtCompleted}</span>
            <span class="case-process-legend-future">${uiLabelMap.CasemgmtFuture}</span>
            <span class="case-process-legend-hint">${uiLabelMap.CasemgmtProcessMapHint}</span>
        </div>
        <div class="mermaid case-process-diagram" role="img" aria-label="${uiLabelMap.CasemgmtProcessMap}">
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
            window.mermaid.run({ querySelector: '.case-process-diagram' }).catch(function () {
                // The table view below remains usable when the diagram cannot render.
            });
        }());
        (function () {
            function setupViewSwitch() {
                var graph = document.querySelector('.case-process-map');
                var tables = document.getElementById('case-process-tables');
                var buttons = document.querySelectorAll('.case-process-view-button');
                if (!graph || !tables || buttons.length === 0) {
                    return;
                }
                function showView(view) {
                    var showGraph = view !== 'table';
                    graph.style.display = showGraph ? '' : 'none';
                    tables.style.display = showGraph ? 'none' : '';
                    Array.prototype.forEach.call(buttons, function (button) {
                        var active = button.getAttribute('data-case-view') === view;
                        button.classList.toggle('is-active', active);
                        button.setAttribute('aria-pressed', active ? 'true' : 'false');
                    });
                }
                Array.prototype.forEach.call(buttons, function (button) {
                    button.addEventListener('click', function () {
                        showView(button.getAttribute('data-case-view'));
                    });
                });
                showView('diagram');
            }
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', setupViewSwitch);
            } else {
                setupViewSwitch();
            }
        }());
    </script>
    <style>
        .case-process-view-switch {
            display: flex;
            gap: 4px;
            margin-bottom: 8px;
        }
        .case-process-view-button {
            border: 1px solid #adb5bd;
            background: #fff;
            color: #212529;
            border-radius: 3px;
            padding: 4px 10px;
            font-size: 12px;
            cursor: pointer;
        }
        .case-process-view-button.is-active {
            background: #0d6efd;
            border-color: #0d6efd;
            color: #fff;
        }
        .case-process-map {
            overflow: auto;
            max-height: 70vh;
            border: 1px solid #d9dee3;
            border-radius: 4px;
            padding: 10px;
            margin-bottom: 14px;
            background: #fff;
        }
        .case-process-diagram {
            min-width: 640px;
        }
        .case-process-legend {
            display: flex;
            gap: 14px;
            align-items: center;
            margin-bottom: 8px;
            font-size: 12px;
        }
        .case-process-legend span {
            display: inline-flex;
            align-items: center;
            gap: 4px;
        }
        .case-process-legend span::before {
            content: "";
            display: inline-block;
            width: 12px;
            height: 12px;
            border: 1px solid #999;
            border-radius: 2px;
        }
        .case-process-legend-current::before { background: #fff3cd; border-color: #d39e00 !important; }
        .case-process-legend-completed::before { background: #d4edda; border-color: #2e7d32 !important; }
        .case-process-legend-future::before { background: #f8f9fa; border-color: #adb5bd !important; }
        .case-process-legend-hint { color: #6c757d; margin-left: auto; }
    </style>
</#if>
