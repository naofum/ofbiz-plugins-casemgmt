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
package org.apache.ofbiz.casemgmt.workeffort

/**
 * Per-thread authorization flag for CASE work effort writes.
 *
 * <p>The entity ECA guard rejects CASE/CASE_ASSIGNMENT writes unless a
 * casemgmt service has set this flag. This keeps generic WorkEffort services
 * from creating or changing case roots and assignments.</p>
 */
final class CaseWorkEffortContext {

    private static final ThreadLocal<Boolean> AUTHORIZED = new ThreadLocal<>()

    private CaseWorkEffortContext() {
    }

    static Object runAuthorized(Closure block) {
        Boolean previous = AUTHORIZED.get()
        AUTHORIZED.set(Boolean.TRUE)
        try {
            return block.call()
        } finally {
            if (previous == null) {
                AUTHORIZED.remove()
            } else {
                AUTHORIZED.set(previous)
            }
        }
    }

    static boolean isAuthorized() {
        return Boolean.TRUE.equals(AUTHORIZED.get())
    }
}
