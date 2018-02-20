/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.sweep.metrics;

import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.logging.LoggingArgs;
import com.palantir.tritium.metrics.registry.MetricName;
import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;

public class SweepMetricImpl<T> implements SweepMetric<T> {
    private final String name;
    private final MetricRegistry metricRegistry;
    private final TaggedMetricRegistry taggedMetricRegistry;
    private final boolean tagWithTableName;
    private final SweepMetricAdapter<?, T> metricAdapter;

    SweepMetricImpl(SweepMetricConfig<T> config) {
        this.name = config.name();
        this.metricRegistry = config.metricRegistry();
        this.taggedMetricRegistry = config.taggedMetricRegistry();
        this.tagWithTableName = config.tagWithTableName();
        this.metricAdapter = config.metricAdapter();
    }

    @Override
    public void set(T value, TableReference tableRef) {
        setMetric(value, tableRef);
    }

    @Override
    public void update(T value, TableReference tableRef) {
        updateMetric(value, tableRef);
    }

    private void setMetric(T value, TableReference tableRef) {
        if (!tagWithTableName) {
            metricAdapter.setNonTaggedMetric(metricRegistry, name, value);
        } else {
            metricAdapter.setTaggedMetric(taggedMetricRegistry, getTaggedMetricName(name, tableRef), value);
        }
    }

    private void updateMetric(T value, TableReference tableRef) {
        if (!tagWithTableName) {
            metricAdapter.updateNonTaggedMetric(metricRegistry, name, value);
        } else {
            metricAdapter.updateTaggedMetric(taggedMetricRegistry, getTaggedMetricName(name, tableRef), value);
        }
    }


    @VisibleForTesting
    static MetricName getTaggedMetricName(String name, TableReference tableRef) {
        TableReference safeTableRef = LoggingArgs.safeTableOrPlaceholder(tableRef);
        return MetricName.builder()
                .safeName(name)
                .safeTags(ImmutableMap.of("tableRef", safeTableRef.toString()))
                .build();
    }
}
