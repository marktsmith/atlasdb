/*
 * Copyright 2015 Palantir Technologies, Inc. All rights reserved.
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
package com.palantir.atlasdb.sweep;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.encoding.PtBytes;
import com.palantir.atlasdb.keyvalue.api.CandidateCellForSweeping;
import com.palantir.atlasdb.keyvalue.api.CandidateCellForSweepingRequest;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.ImmutableCandidateCellForSweepingRequest;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.SweepResults;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.logging.LoggingArgs;
import com.palantir.atlasdb.protos.generated.TableMetadataPersistence.SweepStrategy;
import com.palantir.atlasdb.sweep.CellsToSweepPartitioningIterator.ExaminedCellLimit;
import com.palantir.atlasdb.transaction.impl.SweepStrategyManager;
import com.palantir.atlasdb.transaction.service.TransactionService;
import com.palantir.common.base.ClosableIterator;
import com.palantir.logsafe.UnsafeArg;

import gnu.trove.TDecorators;

/**
 * Sweeps one individual table.
 */
public class SweepTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(SweepTaskRunner.class);

    private final KeyValueService keyValueService;
    private final LongSupplier unreadableTimestampSupplier;
    private final LongSupplier immutableTimestampSupplier;
    private final TransactionService transactionService;
    private final SweepStrategyManager sweepStrategyManager;
    private final CellsSweeper cellsSweeper;

    public SweepTaskRunner(
            KeyValueService keyValueService,
            LongSupplier unreadableTimestampSupplier,
            LongSupplier immutableTimestampSupplier,
            TransactionService transactionService,
            SweepStrategyManager sweepStrategyManager,
            CellsSweeper cellsSweeper) {
        this.keyValueService = keyValueService;
        this.unreadableTimestampSupplier = unreadableTimestampSupplier;
        this.immutableTimestampSupplier = immutableTimestampSupplier;
        this.transactionService = transactionService;
        this.sweepStrategyManager = sweepStrategyManager;
        this.cellsSweeper = cellsSweeper;
    }

    /**
     * Represents the type of run to be conducted by the sweep runner.
     */
    private enum RunType {
        /**
         * A DRY run is expect to not mutate any tables (with the exception of the sweep.progress table)
         * but will determine and report back all the values that *would* have been swept.
         */
        DRY,

        /**
         * A FULL run will execute all followers / sentinel additions / and deletions on the
         * cells that qualify for sweeping.
         */
        FULL
    }

    public SweepResults dryRun(TableReference tableRef,
                               SweepBatchConfig batchConfig,
                               byte[] startRow) {
        return runInternal(tableRef, batchConfig, startRow, RunType.DRY);
    }

    public SweepResults run(TableReference tableRef, SweepBatchConfig batchConfig, byte[] startRow) {
        return runInternal(tableRef, batchConfig, startRow, RunType.FULL);
    }

    public long getConservativeSweepTimestamp() {
        return Sweeper.CONSERVATIVE.getSweepTimestampSupplier().getSweepTimestamp(
                unreadableTimestampSupplier, immutableTimestampSupplier);
    }

    private SweepResults runInternal(
            TableReference tableRef,
            SweepBatchConfig batchConfig,
            byte[] startRow,
            RunType runType) {

        Preconditions.checkNotNull(tableRef, "tableRef cannot be null");
        Preconditions.checkState(!AtlasDbConstants.hiddenTables.contains(tableRef));

        if (tableRef.getQualifiedName().startsWith(AtlasDbConstants.NAMESPACE_PREFIX)) {
            // this happens sometimes; I think it's because some places in the code can
            // start this sweeper without doing the full normally ordered KVSModule startup.
            // I did check and sweep.stats did contain the FQ table name for all of the tables,
            // so it is at least broken in some way that still allows namespaced tables to eventually be swept.
            log.warn("The sweeper should not be run on tables passed through namespace mapping.");
            return SweepResults.createEmptySweepResultWithNoMoreToSweep();
        }
        if (keyValueService.getMetadataForTable(tableRef).length == 0) {
            log.warn("The sweeper tried to sweep table '{}', but the table does not exist. Skipping table.",
                    LoggingArgs.tableRef("tableRef", tableRef));
            return SweepResults.createEmptySweepResultWithNoMoreToSweep();
        }
        SweepStrategy sweepStrategy = sweepStrategyManager.get().getOrDefault(tableRef, SweepStrategy.CONSERVATIVE);
        Optional<Sweeper> sweeper = Sweeper.of(sweepStrategy);
        if (!sweeper.isPresent()) {
            return SweepResults.createEmptySweepResultWithNoMoreToSweep();
        }
        return doRun(tableRef, batchConfig, startRow, runType, sweeper.get());
    }

    private SweepResults doRun(TableReference tableRef,
                               SweepBatchConfig batchConfig,
                               byte[] startRow,
                               RunType runType,
                               Sweeper sweeper) {
        Stopwatch watch = Stopwatch.createStarted();
        long timeSweepStarted = System.currentTimeMillis();
        log.info("Beginning iteration of sweep for table {} starting at row {}",
                LoggingArgs.tableRef(tableRef),
                UnsafeArg.of("startRow", PtBytes.encodeHexString(startRow)));
        // Earliest start timestamp of any currently open transaction, with two caveats:
        // (1) unreadableTimestamps are calculated via wall-clock time, and so may not be correct
        //     under pathological clock conditions
        // (2) immutableTimestamps do not account for locks have timed out after checking their locks;
        //     such a transaction may have a start timestamp less than the immutableTimestamp, and it
        //     could still get successfully committed (its commit timestamp may or may not be less than
        //     the immutableTimestamp
        // Note that this is fine, because we'll either
        // (1) force old readers to abort (if they read a garbage collection sentinel), or
        // (2) force old writers to retry (note that we must roll back any uncommitted transactions that
        //     we encounter
        long sweepTs = sweeper.getSweepTimestampSupplier().getSweepTimestamp(
                unreadableTimestampSupplier, immutableTimestampSupplier);
        CandidateCellForSweepingRequest request = ImmutableCandidateCellForSweepingRequest.builder()
                .startRowInclusive(startRow)
                .batchSizeHint(batchConfig.candidateBatchSize())
                .maxTimestampExclusive(sweepTs)
                .shouldCheckIfLatestValueIsEmpty(sweeper.shouldSweepLastCommitted())
                .timestampsToIgnore(sweeper.getTimestampsToIgnore())
                .build();

        SweepableCellFilter sweepableCellFilter = new SweepableCellFilter(transactionService, sweeper, sweepTs);
        try (ClosableIterator<List<CandidateCellForSweeping>> candidates = keyValueService.getCandidateCellsForSweeping(
                    tableRef, request)) {
            ExaminedCellLimit limit = new ExaminedCellLimit(startRow, batchConfig.maxCellTsPairsToExamine());
            Iterator<BatchOfCellsToSweep> batchesToSweep = getBatchesToSweep(
                        candidates, batchConfig, sweepableCellFilter, limit);
            long totalCellTsPairsExamined = 0;
            long totalCellTsPairsDeleted = 0;
            byte[] lastRow = startRow;
            while (batchesToSweep.hasNext()) {
                BatchOfCellsToSweep batch = batchesToSweep.next();

                /*
                 * At this point cells were merged in batches of at least deleteBatchSize blocks per batch. Therefore we
                 * expect most batches to have slightly more than deleteBatchSize blocks. Partitioning such batches with
                 * deleteBatchSize as a limit results in a small second batch, which is bad for performance reasons.
                 * Therefore, deleteBatchSize is doubled.
                 */
                totalCellTsPairsDeleted += sweepBatch(tableRef, batch.cells(), runType,
                        2 * batchConfig.deleteBatchSize());

                totalCellTsPairsExamined += batch.numCellTsPairsExamined();
                lastRow = batch.lastCellExamined().getRowName();
            }
            return SweepResults.builder()
                    .previousStartRow(Optional.of(startRow))
                    .nextStartRow(Arrays.equals(startRow, lastRow) ? Optional.empty() : Optional.of(lastRow))
                    .cellTsPairsExamined(totalCellTsPairsExamined)
                    .staleValuesDeleted(totalCellTsPairsDeleted)
                    .minSweptTimestamp(sweepTs)
                    .timeInMillis(watch.elapsed(TimeUnit.MILLISECONDS))
                    .timeSweepStarted(timeSweepStarted)
                    .build();
        }
    }

    /**
     * Returns batches with at least batchConfig.deleteBatchSize blocks per batch.
     */
    private Iterator<BatchOfCellsToSweep> getBatchesToSweep(Iterator<List<CandidateCellForSweeping>> candidates,
                                                            SweepBatchConfig batchConfig,
                                                            SweepableCellFilter sweepableCellFilter,
                                                            ExaminedCellLimit limit) {
        Iterator<BatchOfCellsToSweep> cellsToSweep = Iterators.transform(
                Iterators.filter(candidates, list -> !list.isEmpty()),
                sweepableCellFilter::getCellsToSweep);
        return new CellsToSweepPartitioningIterator(cellsToSweep, batchConfig.deleteBatchSize(), limit);
    }

    /**
     * Returns the number of blocks - (cell, timestamp) pairs - that were deleted.
     */
    private int sweepBatch(TableReference tableRef, List<CellToSweep> batch, RunType runType,
            int deleteBatchSize) {
        int numberOfSweptCells = 0;

        Multimap<Cell, Long> currentBatch = ArrayListMultimap.create();
        List<Cell> currentBatchSentinels = Lists.newArrayList();

        for (CellToSweep cell : batch) {
            if (cell.needsSentinel()) {
                currentBatchSentinels.add(cell.cell());
            }

            List<Long> currentCellTimestamps = TDecorators.wrap(cell.sortedTimestamps());

            if (currentBatch.size() + currentCellTimestamps.size() < deleteBatchSize) {
                currentBatch.putAll(cell.cell(), currentCellTimestamps);
                continue;
            }

            while (currentBatch.size() + currentCellTimestamps.size() >= deleteBatchSize) {
                int numberOfTimestampsForThisBatch = deleteBatchSize - currentBatch.size();

                currentBatch.putAll(cell.cell(), currentCellTimestamps.subList(0, numberOfTimestampsForThisBatch));
                if (runType == RunType.FULL) {
                    cellsSweeper.sweepCells(tableRef, currentBatch, currentBatchSentinels);
                }
                numberOfSweptCells += currentBatch.size();

                currentBatch.clear();
                currentBatchSentinels.clear();
                currentCellTimestamps = currentCellTimestamps.subList(
                        numberOfTimestampsForThisBatch, currentCellTimestamps.size());
            }
            currentBatch.putAll(cell.cell(), currentCellTimestamps);
        }

        if (!currentBatch.isEmpty() && runType == RunType.FULL) {
            cellsSweeper.sweepCells(tableRef, currentBatch, currentBatchSentinels);
        }
        numberOfSweptCells += currentBatch.size();

        return numberOfSweptCells;
    }
}
