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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertThat;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableMap;
import com.palantir.atlasdb.AtlasDbMetricNames;
import com.palantir.atlasdb.keyvalue.api.ImmutableSweepResults;
import com.palantir.atlasdb.keyvalue.api.SweepResults;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.logging.LoggingArgs;
import com.palantir.atlasdb.protos.generated.TableMetadataPersistence;
import com.palantir.atlasdb.table.description.ColumnMetadataDescription;
import com.palantir.atlasdb.table.description.NameMetadataDescription;
import com.palantir.atlasdb.table.description.TableMetadata;
import com.palantir.atlasdb.transaction.api.ConflictHandler;
import com.palantir.atlasdb.util.AtlasDbMetrics;
import com.palantir.atlasdb.util.CurrentValueMetric;
import com.palantir.tritium.metrics.registry.DefaultTaggedMetricRegistry;

public class SweepMetricsManagerTest {
    private static final long DELETED = 10L;
    private static final long EXAMINED = 15L;
    private static final long TIME_SWEEPING = 100L;
    private static final long START_TIME = 100_000L;

    private static final long OTHER_DELETED = 12L;
    private static final long OTHER_EXAMINED = 4L;
    private static final long OTHER_TIME_SWEEPING = 200L;
    private static final long OTHER_START_TIME = 1_000_000L;

    private static final TableReference TABLE_REF = TableReference.createFromFullyQualifiedName("sweep.test");
    private static final TableReference TABLE_REF2 = TableReference.createFromFullyQualifiedName("sweep.test2");

    private static final String CELLS_EXAMINED = AtlasDbMetricNames.CELLS_EXAMINED;
    private static final String CELLS_SWEPT = AtlasDbMetricNames.CELLS_SWEPT;
    private static final String TIME_SPENT_SWEEPING = AtlasDbMetricNames.TIME_SPENT_SWEEPING;
    private static final String TABLE_BEING_SWEPT = AtlasDbMetricNames.TABLE_BEING_SWEPT;

    private static final String TABLE_FULLY_QUALIFIED = TABLE_REF.getQualifiedName();
    private static final String TABLE2_FULLY_QUALIFIED = TABLE_REF2.getQualifiedName();
    private static final String UNSAFE_FULLY_QUALIFIED = LoggingArgs.PLACEHOLDER_TABLE_REFERENCE.getQualifiedName();


    private static final SweepResults SWEEP_RESULTS = ImmutableSweepResults.builder()
            .cellTsPairsExamined(EXAMINED)
            .staleValuesDeleted(DELETED)
            .timeInMillis(TIME_SWEEPING)
            .timeSweepStarted(START_TIME)
            .minSweptTimestamp(0L)
            .build();

    private static final SweepResults OTHER_SWEEP_RESULTS = ImmutableSweepResults.builder()
            .cellTsPairsExamined(OTHER_EXAMINED)
            .staleValuesDeleted(OTHER_DELETED)
            .timeInMillis(OTHER_TIME_SWEEPING)
            .timeSweepStarted(OTHER_START_TIME)
            .minSweptTimestamp(0L)
            .build();

    private static final byte[] SAFE_METADATA = createTableMetadataWithLogSafety(
            TableMetadataPersistence.LogSafety.SAFE).persistToBytes();

    private static final byte[] UNSAFE_METADATA = createTableMetadataWithLogSafety(
            TableMetadataPersistence.LogSafety.UNSAFE).persistToBytes();

    private static MetricRegistry metricRegistry;

    private SweepMetricsManager sweepMetricsManager;

    @Before
    public void setUp() {
        sweepMetricsManager = new SweepMetricsManager();
        metricRegistry = AtlasDbMetrics.getMetricRegistry();
    }

    @After
    public void tearDown() {
        AtlasDbMetrics.setMetricRegistries(new MetricRegistry(),
                new DefaultTaggedMetricRegistry());
    }

    @Test
    public void allGaugesAreUpdatedForSafeTables() {
        setLoggingSafety(ImmutableMap.of(TABLE_REF, SAFE_METADATA, TABLE_REF2, SAFE_METADATA));
        sweepMetricsManager.updateMetrics(SWEEP_RESULTS, TABLE_REF, UpdateEventType.ONE_ITERATION);

        assertRecordedCurrentValue(ImmutableMap.of(
                CELLS_EXAMINED, EXAMINED,
                CELLS_SWEPT, DELETED,
                TIME_SPENT_SWEEPING, TIME_SWEEPING,
                TABLE_BEING_SWEPT, TABLE_FULLY_QUALIFIED));

        sweepMetricsManager.updateMetrics(OTHER_SWEEP_RESULTS, TABLE_REF2, UpdateEventType.ONE_ITERATION);

        assertRecordedCurrentValue(ImmutableMap.of(
                CELLS_EXAMINED, OTHER_EXAMINED,
                CELLS_SWEPT, OTHER_DELETED,
                TIME_SPENT_SWEEPING, OTHER_TIME_SWEEPING,
                TABLE_BEING_SWEPT, TABLE2_FULLY_QUALIFIED));
    }

    @Test
    public void allGaugesAreUpdatedForUnsafeTables() {
        setLoggingSafety(ImmutableMap.of(TABLE_REF, UNSAFE_METADATA, TABLE_REF2, UNSAFE_METADATA));
        sweepMetricsManager.updateMetrics(SWEEP_RESULTS, TABLE_REF, UpdateEventType.ONE_ITERATION);

        assertRecordedCurrentValue(ImmutableMap.of(
                CELLS_EXAMINED, EXAMINED,
                CELLS_SWEPT, DELETED,
                TIME_SPENT_SWEEPING, TIME_SWEEPING,
                TABLE_BEING_SWEPT, UNSAFE_FULLY_QUALIFIED));

        sweepMetricsManager.updateMetrics(OTHER_SWEEP_RESULTS, TABLE_REF2, UpdateEventType.ONE_ITERATION);

        assertRecordedCurrentValue(ImmutableMap.of(
                CELLS_EXAMINED, OTHER_EXAMINED,
                CELLS_SWEPT, OTHER_DELETED,
                TIME_SPENT_SWEEPING, OTHER_TIME_SWEEPING,
                TABLE_BEING_SWEPT, UNSAFE_FULLY_QUALIFIED));
    }

    @Test
    public void allGaugesAreUpdatedForUnknownSafetyAsUnsafe() {
        setLoggingSafety(ImmutableMap.of());
        sweepMetricsManager.updateMetrics(SWEEP_RESULTS, TABLE_REF, UpdateEventType.ONE_ITERATION);

        assertRecordedCurrentValue(ImmutableMap.of(
                CELLS_EXAMINED, EXAMINED,
                CELLS_SWEPT, DELETED,
                TIME_SPENT_SWEEPING, TIME_SWEEPING,
                TABLE_BEING_SWEPT, UNSAFE_FULLY_QUALIFIED));

        sweepMetricsManager.updateMetrics(OTHER_SWEEP_RESULTS, TABLE_REF2, UpdateEventType.ONE_ITERATION);

        assertRecordedCurrentValue(ImmutableMap.of(
                CELLS_EXAMINED, OTHER_EXAMINED,
                CELLS_SWEPT, OTHER_DELETED,
                TIME_SPENT_SWEEPING, OTHER_TIME_SWEEPING,
                TABLE_BEING_SWEPT, UNSAFE_FULLY_QUALIFIED));
    }

    @Test
    public void timeElapsedGaugeIsUpdatedToNewestValueForOneIteration() {
        sweepMetricsManager.updateMetrics(SWEEP_RESULTS, TABLE_REF, UpdateEventType.ONE_ITERATION);
        assertSweepTimeElapsedCurrentValueWithinMarginOfError(START_TIME);
        sweepMetricsManager.updateMetrics(OTHER_SWEEP_RESULTS, TABLE_REF2, UpdateEventType.ONE_ITERATION);
        assertSweepTimeElapsedCurrentValueWithinMarginOfError(OTHER_START_TIME);
    }

    @Test
    public void testSweepError() {
        sweepMetricsManager.sweepError();
        sweepMetricsManager.sweepError();

        Meter meter = getMeter(AtlasDbMetricNames.SWEEP_ERROR, UpdateEventType.ERROR);
        assertThat(meter.getCount(), equalTo(2L));
    }

    private void setLoggingSafety(Map<TableReference, byte[]> args) {
        LoggingArgs.hydrate(args);
    }

    private void assertRecordedCurrentValue(Map<String, ?> metricToValue) {
        for (Map.Entry<String, ?> entry: metricToValue.entrySet()) {
            Gauge<?> gauge = getCurrentValueMetric(entry.getKey());
            assertThat(gauge.getValue(), equalTo(entry.getValue()));
        }
    }

    private void assertSweepTimeElapsedCurrentValueWithinMarginOfError(long timeSweepStarted) {
        Gauge<Long> gauge = getCurrentValueMetric(AtlasDbMetricNames.TIME_ELAPSED_SWEEPING);
        assertWithinErrorMarginOf(gauge.getValue(), System.currentTimeMillis() - timeSweepStarted);
    }

    private Meter getMeter(String namePrefix, UpdateEventType updateEvent) {
        return metricRegistry.meter(MetricRegistry.name(SweepMetric.class, namePrefix,
                SweepMetricAdapter.METER_ADAPTER.getNameComponent(), updateEvent.getNameComponent()));
    }

    private Gauge getCurrentValueMetric(String namePrefix) {
        return metricRegistry.gauge(
                MetricRegistry.name(SweepMetric.class, namePrefix,
                        SweepMetricAdapter.CURRENT_VALUE_ADAPTER_LONG.getNameComponent(),
                        UpdateEventType.ONE_ITERATION.getNameComponent()),
                CurrentValueMetric::new);
    }

    private void assertWithinErrorMarginOf(long actual, long expected) {
        assertThat(actual, greaterThan((long) (expected * .95)));
        assertThat(actual, lessThanOrEqualTo((long) (expected * 1.05)));
    }

    private static TableMetadata createTableMetadataWithLogSafety(TableMetadataPersistence.LogSafety safety) {
        return new TableMetadata(
                new NameMetadataDescription(),
                new ColumnMetadataDescription(),
                ConflictHandler.RETRY_ON_WRITE_WRITE,
                TableMetadataPersistence.CachePriority.WARM,
                false,
                0,
                false,
                TableMetadataPersistence.SweepStrategy.CONSERVATIVE,
                false,
                safety);
    }
}
