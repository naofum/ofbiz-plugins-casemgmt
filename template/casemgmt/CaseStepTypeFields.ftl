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
<script>
    (function () {
        function hideField(form, name, visible) {
            var field = form.querySelector('[name="' + name + '"]');
            if (!field) {
                return;
            }
            var row = field.closest('tr') || field.parentNode;
            if (row) {
                row.style.display = visible ? '' : 'none';
            }
        }

        function applyStepType(form) {
            var type = form.querySelector('[name="stepTypeEnumId"]');
            if (!type) {
                return;
            }
            var value = type.value;
            var human = value === 'CASE_STEP_HUMAN_TASK';
            var service = value === 'CASE_STEP_SERVICE' || value === 'CASE_STEP_DECISION';
            var subCase = value === 'CASE_STEP_SUB_CASE';
            var wait = value === 'CASE_STEP_WAIT';
            hideField(form, 'serviceName', service);
            hideField(form, 'formId', human);
            hideField(form, 'subCaseTypeId', subCase);
            hideField(form, 'signalName', wait);
            hideField(form, 'timeoutDuration', wait);
            hideField(form, 'timeoutOutcomeEnumId', wait);
            hideField(form, 'assignmentPolicyId', human);
            hideField(form, 'slaId', human);
        }

        function setup() {
            ['CreateCaseStep', 'EditCaseStep'].forEach(function (formId) {
                var form = document.getElementById(formId);
                if (!form) {
                    return;
                }
                var type = form.querySelector('[name="stepTypeEnumId"]');
                if (!type) {
                    return;
                }
                type.addEventListener('change', function () {
                    applyStepType(form);
                });
                applyStepType(form);
            });
        }

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', setup);
        } else {
            setup();
        }
    }());
</script>
