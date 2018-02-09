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
import com.palantir.atlasdb.util.MetricsManager;
import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;

public class SweepMetricsFactory {
    private final MetricRegistry metricRegistry = new MetricsManager().getRegistry();
    private final TaggedMetricRegistry taggedMetricRegistry = new MetricsManager().getTaggedRegistry();

    SweepMetric<Long> simpleLong(String namePrefix) {
        return createCurrentValueLong(namePrefix, UpdateEventType.ONE_ITERATION, false);
    }

    SweepMetric<String> simpleString(String namePrefix) {
        return createCurrentValueString(namePrefix, UpdateEventType.ONE_ITERATION, false);
    }

    SweepMetric<Long> accumulatingLong(String namePrefix) {
        return createAccumulatingLong(namePrefix, UpdateEventType.ONE_ITERATION, false);
    }

    /**
     * Creates a SweepMetric backed by a Meter. The name of the metric is the concatenation
     * SweepMetric.class.getName() + namePrefix + "Meter" + updateEvent.nameComponent().
     *
     * @param namePrefix Determines the prefix of the metric name.
     * @param updateEvent Determines on which type of event the metric should be updated and determines the suffix of
     *                    the metric name.
     * @param tagWithTableName If true, metric will also be tagged with the table name. If false, the metric will not be
     *                         tagged.
     * @return SweepMetric backed by a Meter
     */
    SweepMetric<Long> createMeter(String namePrefix, UpdateEventType updateEvent, boolean tagWithTableName) {
        return createMetric(namePrefix, updateEvent, tagWithTableName, SweepMetricAdapter.METER_ADAPTER);
    }

    /**
     * Creates a SweepMetric backed by a CurrentValueMetric. The name of the metric is the concatenation
     * SweepMetric.class.getName() + namePrefix + "CurrentValue" + updateEvent.nameComponent().
     *
     * @param namePrefix Determines the prefix of the metric name.
     * @param updateEvent Determines on which type of event the metric should be updated and determines the suffix of
     *                    the metric name.
     * @param tag If true, metric will also be tagged with the table name. If false, the metric will not be
     *                         tagged.
     * @return SweepMetric backed by a CurrentValueMetric
     */
    SweepMetric<Long> createCurrentValueLong(String namePrefix, UpdateEventType updateEvent, boolean tag) {
        return createMetric(namePrefix, updateEvent, tag, SweepMetricAdapter.CURRENT_VALUE_ADAPTER_LONG);
    }

    SweepMetric<String> createCurrentValueString(String namePrefix, UpdateEventType updateEvent, boolean tag) {
        return createMetric(namePrefix, updateEvent, tag, SweepMetricAdapter.CURRENT_VALUE_ADAPTER_STRING);
    }

    SweepMetric<Long> createAccumulatingLong(String namePrefix, UpdateEventType updateEvent, boolean tag) {
        return createMetric(namePrefix, updateEvent, tag, SweepMetricAdapter.ACCUMULATING_VALUE_METRIC_ADAPTER);
    }

    private <T> SweepMetric<T> createMetric(String namePrefix, UpdateEventType updateEvent, boolean tagWithTableName,
            SweepMetricAdapter<?, T> metricAdapter) {
        return new SweepMetricImpl<>(ImmutableSweepMetricConfig.<T>builder()
                .namePrefix(namePrefix)
                .metricRegistry(metricRegistry)
                .taggedMetricRegistry(taggedMetricRegistry)
                .updateEvent(updateEvent)
                .tagWithTableName(tagWithTableName)
                .metricAdapter(metricAdapter)
                .build());
    }
}
