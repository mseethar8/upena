/*
 * Copyright 2013 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.os.upena.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import com.jivesoftware.os.upena.amza.service.AmzaTable;
import com.jivesoftware.os.upena.amza.shared.RowIndexKey;
import com.jivesoftware.os.upena.shared.BasicTimestampedValue;
import com.jivesoftware.os.upena.shared.Key;
import com.jivesoftware.os.upena.shared.KeyValueFilter;
import com.jivesoftware.os.upena.shared.Stored;
import com.jivesoftware.os.upena.shared.TimestampedValue;
import java.util.List;
import java.util.concurrent.ConcurrentNavigableMap;

public class UpenaTable<K extends Key, V extends Stored> implements UpenaMap<K, V> {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();


    private final ObjectMapper mapper;
    private final AmzaTable store;
    private final Class<K> keyClass;
    private final Class<V> valueClass;
    private final UpenaKeyProvider<K, V> keyProvider;
    private final UpenaValueValidator<K, V> valueValidator;

    public UpenaTable(ObjectMapper mapper,
        AmzaTable store,
        Class<K> keyClass,
        Class<V> valueClass,
        UpenaKeyProvider<K, V> keyProvider,
        UpenaValueValidator<K, V> valueValidator) {

        this.mapper = mapper;
        this.store = store;
        this.keyClass = keyClass;
        this.valueClass = valueClass;
        this.keyProvider = keyProvider;
        this.valueValidator = valueValidator;
    }


    @Override
    public void putIfAbsent(K key, V value) throws Exception {
        if (get(key) == null) {
            update(key, value);
        }
    }

    @Override
    public V get(K key) throws Exception {
        byte[] rawKey = mapper.writeValueAsBytes(key);
        byte[] got = store.get(new RowIndexKey(rawKey));
        if (got == null) {
            return null;
        }
        return mapper.readValue(got, valueClass);
    }

    @Override
    public void scan(final Stream<K, V> stream) throws Exception {
        store.scan((l, key, value) -> {
            if (!value.getTombstoned()) {
                K k = mapper.readValue(key.getKey(), keyClass);
                V v = mapper.readValue(value.getValue(), valueClass);
                return stream.stream(k, v);
            } else {
                return true;
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public ConcurrentNavigableMap<K, TimestampedValue<V>> find(boolean removeBadKeysEnabled, final KeyValueFilter<K, V> filter) throws Exception {
        final ConcurrentNavigableMap<K, TimestampedValue<V>> results = filter == null ? null : filter.createCollector();
        List<RowIndexKey> badKeys = Lists.newArrayList();
        store.scan((transactionId, key, value) -> {
            K k = null;
            try {
                if (!value.getTombstoned()) {
                    k = mapper.readValue(key.getKey(), keyClass);
                }
            } catch (Exception x) {
                LOG.warn("Failed to scan. {}", new Object[]{filter}, x);
                badKeys.add(key);
            }

            if (k != null) {
                V v = mapper.readValue(value.getValue(), valueClass);
                if (results != null && filter != null && filter.filter(k, v)) {
                    results.put(k, new BasicTimestampedValue(v, value.getTimestampId(), value.getTombstoned()));
                }
            }
            return true;
        });
        if (!badKeys.isEmpty()) {
            if (removeBadKeysEnabled) {
                for (RowIndexKey key : badKeys) {
                    store.remove(key);
                }
                LOG.info("Removed {} bad keys", badKeys.size());
            } else {
                LOG.warn("Find for filter:{} has {} bad keys.", filter, badKeys.size());
            }
        }
        return results;
    }

    @Override
    synchronized public K update(K key, V value) throws Exception {
        if (key == null) {
            key = keyProvider.getNodeKey(this, value);
        }
        if (valueValidator != null) {
            value = valueValidator.validate(this, key, value);
        }
        byte[] rawKey = mapper.writeValueAsBytes(key);
        byte[] rawValue = mapper.writeValueAsBytes(value);
        RowIndexKey set = store.set(new RowIndexKey(rawKey), rawValue);
        K gotKey = mapper.readValue(set.getKey(), keyClass);
        return gotKey;
    }

    @Override
    synchronized public boolean remove(K key) throws Exception {
        byte[] rawKey = mapper.writeValueAsBytes(key);
        return store.remove(new RowIndexKey(rawKey));
    }
}
