// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

package com.starrocks.sql.optimizer.statistics;

import com.google.common.base.Preconditions;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;

import java.util.HashMap;
import java.util.Map;

import static java.lang.Double.NaN;

public class Statistics {
    private final double outputRowCount;
    private final Map<ColumnRefOperator, ColumnStatistic> columnStatistics;

    public Statistics(double outputRowCount,
                      Map<ColumnRefOperator, ColumnStatistic> columnStatistics) {
        this.outputRowCount = outputRowCount;
        this.columnStatistics = columnStatistics;
    }

    public double getOutputRowCount() {
        return outputRowCount;
    }

    public double getOutputSize() {
        return this.columnStatistics.values().stream().map(ColumnStatistic::getAverageRowSize).
                reduce(0.0, Double::sum) * outputRowCount;
    }

    public ColumnStatistic getColumnStatistic(ColumnRefOperator column) {
        ColumnStatistic result = columnStatistics.get(column);
        Preconditions.checkState(result != null);
        return result;
    }

    public Map<ColumnRefOperator, ColumnStatistic> getColumnStatistics() {
        return columnStatistics;
    }

    public ColumnRefSet getUsedColumns() {
        ColumnRefSet usedColumns = new ColumnRefSet();
        for (Map.Entry<ColumnRefOperator, ColumnStatistic> entry : columnStatistics.entrySet()) {
            usedColumns.union(entry.getKey().getUsedColumns());
        }
        return usedColumns;
    }

    public static Builder buildFrom(Statistics other) {
        return new Builder(other.getOutputRowCount(), other.columnStatistics);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private double outputRowCount;
        private final Map<ColumnRefOperator, ColumnStatistic> columnStatistics;

        public Builder() {
            this(NaN, new HashMap<>());
        }

        private Builder(double outputRowCount, Map<ColumnRefOperator, ColumnStatistic> columnStatistics) {
            this.outputRowCount = outputRowCount;
            this.columnStatistics = new HashMap<>(columnStatistics);
        }

        public Builder setOutputRowCount(double outputRowCount) {
            this.outputRowCount = outputRowCount;
            return this;
        }

        public Builder addColumnStatistic(ColumnRefOperator column, ColumnStatistic statistic) {
            this.columnStatistics.put(column, statistic);
            return this;
        }

        public Builder addColumnStatistics(Map<ColumnRefOperator, ColumnStatistic> columnStatistics) {
            this.columnStatistics.putAll(columnStatistics);
            return this;
        }

        public Builder removeColumnStatistics(ColumnRefOperator column) {
            columnStatistics.remove(column);
            return this;
        }

        public Statistics build() {
            return new Statistics(outputRowCount, columnStatistics);
        }
    }
}
