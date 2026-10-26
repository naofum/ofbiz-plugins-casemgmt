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
package org.apache.ofbiz.casemgmt.cases

import org.apache.ofbiz.entity.Delegator
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.model.ModelEntity
import org.apache.ofbiz.entity.util.EntityQuery

/**
 * Shared helpers for single-key and composite-key CaseContext links.
 *
 * New links store their primary-key fields in CaseContextKey. Existing
 * single-key links remain readable through CaseContext.pkValue.
 */
final class CaseContextHelper {

    private CaseContextHelper() {
    }

    static Map<String, String> loadKeys(Delegator delegator, GenericValue context) {
        Map<String, String> keys = new LinkedHashMap<>()
        if (context == null) {
            return keys
        }
        List<GenericValue> keyValues = EntityQuery.use(delegator)
                .from('CaseContextKey')
                .where(EntityCondition.makeCondition([
                        caseId    : context.caseId,
                        contextSeq: context.contextSeq
                ]))
                .orderBy('keySeq')
                .queryList()
        keyValues.each { GenericValue value ->
            keys[value.keyName as String] = value.keyValue as String
        }
        if (keys.isEmpty() && context.pkValue) {
            ModelEntity modelEntity = modelEntity(delegator, context.entityName)
            List<String> pkFieldNames = modelEntity?.getPkFieldNames() ?: []
            if (pkFieldNames.size() == 1) {
                keys[pkFieldNames[0]] = context.pkValue as String
            }
        }
        return keys
    }

    static Map<String, Object> normalizeKeys(Delegator delegator, String entityName,
                                             Map businessKeys, String pkValue) {
        ModelEntity modelEntity = modelEntity(delegator, entityName)
        if (modelEntity == null) {
            return [error: "Entity not found: $entityName".toString()]
        }
        List<String> pkFieldNames = modelEntity.getPkFieldNames()
        Map<String, String> supplied = [:]
        if (businessKeys instanceof Map) {
            (businessKeys as Map).each { Object key, Object value ->
                String text = value?.toString()?.trim()
                if (text) {
                    supplied[key.toString()] = text
                }
            }
        }
        if (supplied.isEmpty() && pkValue?.trim()) {
            if (pkFieldNames.size() != 1) {
                return [error: "Composite primary key requires businessKeys: $entityName".toString()]
            }
            supplied[pkFieldNames[0]] = pkValue.trim()
        }
        if (supplied.isEmpty()) {
            return [error: "Primary key values are required: $entityName".toString()]
        }
        if (supplied.keySet() != new LinkedHashSet<>(pkFieldNames)) {
            return [error: "Primary key fields must be ${pkFieldNames.join(', ')}: $entityName".toString()]
        }
        Map<String, String> ordered = new LinkedHashMap<>()
        pkFieldNames.each { String fieldName ->
            ordered[fieldName] = supplied[fieldName]
        }
        return [
                keys   : ordered,
                pkValue: pkFieldNames.size() == 1 ? ordered[pkFieldNames[0]] : null
        ]
    }

    static GenericValue findTarget(Delegator delegator, String entityName, Map<String, String> keys) {
        if (!entityName || !keys) {
            return null
        }
        try {
            return EntityQuery.use(delegator)
                    .from(entityName)
                    .where(EntityCondition.makeCondition(new LinkedHashMap<String, Object>(keys)))
                    .queryOne()
        } catch (Exception ignored) {
            return null
        }
    }

    static boolean sameKeys(Map<String, String> left, Map<String, String> right) {
        return left != null && right != null && left == right
    }

    static String describeKeys(Map<String, String> keys) {
        if (!keys) {
            return ''
        }
        return keys.collect { String name, String value -> "$name=$value" }.join(', ')
    }

    private static ModelEntity modelEntity(Delegator delegator, String entityName) {
        if (!entityName) {
            return null
        }
        try {
            return delegator.getModelEntity(entityName)
        } catch (Exception ignored) {
            return null
        }
    }
}
