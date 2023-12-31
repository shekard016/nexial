/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.commons.utils;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

public final class CollectionUtil {
    private CollectionUtil() { }

    public static <T> List<T> toList(Set<T> set) {
        List<T> list = new ArrayList<>();
        if (set == null || set.isEmpty()) { return list; }
        list.addAll(set);
        return list;
    }

    public static <X> List<X> generifyList(List list, Class<X> genericType) {
        List<X> genericList = new ArrayList<>();
        if (CollectionUtils.isEmpty(list)) { return genericList; }

        for (Object item : list) {
            if (item == null || genericType.isAssignableFrom(item.getClass())) {
                genericList.add((X) item);
            } else {
                throw new ClassCastException("list item " + item + " cannot be cast to type " + genericType);
            }
        }

        return genericList;
    }

    public static String toString(Collection list, String delim) {
        if (CollectionUtils.isEmpty(list)) { return ""; }
        if (delim == null) { delim = ""; }

        StringBuilder buffer = new StringBuilder();
        for (Object item : list) { buffer.append(item).append(delim); }

        return StringUtils.removeEnd(buffer.toString(), delim);
    }

    public static String toString(Set set, String delim) {
        if (CollectionUtils.isEmpty(set)) { return ""; }
        if (delim == null) { delim = ""; }

        StringBuilder buffer = new StringBuilder();
        for (Iterator iterator = set.iterator(); iterator.hasNext(); ) {
            Object next = iterator.next();
            buffer.append(next == null ? "" : next);
            if (iterator.hasNext()) { buffer.append(delim); }
        }

        return buffer.toString();
    }

    public static <T> T getOrDefault(List<T> list, int index, T defaultIfNone) {
        if (index < 0 || CollectionUtils.size(list) <= index) { return defaultIfNone; }

        T item = list.get(index);
        return item == null ? defaultIfNone : item;
    }

    public static <T> Map<String, T> filterByPrefix(Map<String, T> map, String prefix) {
        Map<String, T> match = new LinkedHashMap<>();
        if (MapUtils.isEmpty(map) || StringUtils.isEmpty(prefix)) { return match; }

        map.forEach((key, value) -> {
            if (StringUtils.startsWith(key, prefix)) { match.put(StringUtils.substringAfter(key, prefix), value); }
        });

        return match;
    }

    public static <T extends Iterable> List<List<String>> transpose(List<T> range) {
        List<List<String>> transposed = new ArrayList<>();

        for (T row : range) {
            if (row == null) { continue; }

            int size = IterableUtils.size(row);
            if (IterableUtils.isEmpty(transposed)) {
                // use first row (original) as guide.. but we'll allow for more rows if more found later in the array
                for (int j = 0; j < size; j++) { transposed.add(new ArrayList<>()); }
            }

            // adjust for wider rows (wider than the first row)
            int additionalRows = Math.max(size - transposed.size(), 0);
            for (int j = 0; j < additionalRows; j++) { transposed.add(new ArrayList<>()); }

            for (int j = 0; j < size; j++) {
                transposed.get(j).add(Objects.toString(IterableUtils.get(row, j)));
            }
        }

        return transposed;
    }

    public static Map<String, String> removeEmptyEntries(Map<String, String> map) {
        if (MapUtils.isEmpty(map)) { return map; }

        String[] keys = map.keySet().toArray(new String[0]);
        Arrays.stream(keys).forEach(key -> {
            if (StringUtils.isEmpty(key) || StringUtils.isEmpty(map.get(key))) { map.remove(key); }
        });

        return map;
    }

    public static <T> T randomSelectOne(Collection<T> collection) {
        if (CollectionUtils.isEmpty(collection)) { return null; }

        int size = collection.size();
        if (size == 1) { return IterableUtils.get(collection, 0); }

        return IterableUtils.get(collection, RandomUtils.nextInt(0, size));
    }
}
