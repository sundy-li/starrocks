// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

package com.starrocks.sql.optimizer.rule.implementation;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptExpressionVisitor;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.physical.PhysicalHashAggregateOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalProjectOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.CaseWhenOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ReplaceColumnRefRewriter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PreAggregateTurnOnRule {
    private static final PreAggregateVisitor VISITOR = new PreAggregateVisitor();

    public static void tryOpenPreAggregate(OptExpression root) {
        root.getOp().accept(VISITOR, root, new PreAggregationContext());
    }

    private static class PreAggregateVisitor extends OptExpressionVisitor<Void, PreAggregationContext> {
        private static final List<String> AGGREGATE_ONLY_KEY = ImmutableList.<String>builder()
                .add("NDV")
                .add("MULTI_DISTINCT_COUNT")
                .add("APPROX_COUNT_DISTINCT")
                .add(FunctionSet.BITMAP_UNION_INT.toUpperCase()).build();

        @Override
        public Void visit(OptExpression optExpression, PreAggregationContext context) {
            // Avoid left child modify context will effect right child
            if (optExpression.getInputs().size() <= 1) {
                for (OptExpression opt : optExpression.getInputs()) {
                    opt.getOp().accept(this, opt, context);
                }
            } else {
                for (OptExpression opt : optExpression.getInputs()) {
                    opt.getOp().accept(this, opt, context.clone());
                }
            }

            return null;
        }

        @Override
        public Void visitPhysicalHashAggregate(OptExpression optExpression, PreAggregationContext context) {
            PhysicalHashAggregateOperator aggregate = (PhysicalHashAggregateOperator) optExpression.getOp();
            // Only save the recently aggregate
            context.aggregations =
                    aggregate.getAggregations().values().stream().map(CallOperator::clone).collect(Collectors.toList());
            context.groupings =
                    aggregate.getGroupBys().stream().map(ScalarOperator::clone).collect(Collectors.toList());
            context.hasJoin = false;

            return visit(optExpression, context);
        }

        @Override
        public Void visitPhysicalProject(OptExpression optExpression, PreAggregationContext context) {
            PhysicalProjectOperator project = (PhysicalProjectOperator) optExpression.getOp();
            ReplaceColumnRefRewriter rewriter = new ReplaceColumnRefRewriter(project.getColumnRefMap());
            ReplaceColumnRefRewriter subRewriter =
                    new ReplaceColumnRefRewriter(project.getCommonSubOperatorMap(), true);

            context.aggregations = context.aggregations.stream()
                    .map(d -> d.accept(rewriter, null).accept(subRewriter, null))
                    .collect(Collectors.toList());

            context.groupings = context.groupings.stream()
                    .map(d -> d.accept(rewriter, null).accept(subRewriter, null))
                    .collect(Collectors.toList());

            return visit(optExpression, context);
        }

        @Override
        public Void visitPhysicalOlapScan(OptExpression optExpression, PreAggregationContext context) {
            PhysicalOlapScanOperator scan = (PhysicalOlapScanOperator) optExpression.getOp();

            // default false
            scan.setPreAggregation(false);

            // Duplicate table
            if (!scan.getTable().getKeysType().isAggregationFamily()) {
                scan.setPreAggregation(true);
                scan.setTurnOffReason("");
                return null;
            }

            if (context.hasJoin) {
                scan.setTurnOffReason("Has Join");
                return null;
            }

            if (context.aggregations.isEmpty() && context.groupings.isEmpty()) {
                scan.setTurnOffReason("None aggregate function");
                return null;
            }

            // check has value conjunct
            boolean allKeyConjunct =
                    Utils.extractColumnRef(scan.getPredicate()).stream().map(ref -> scan.getColumnRefMap().get(ref))
                            .allMatch(Column::isKey);
            if (!allKeyConjunct) {
                scan.setTurnOffReason("Predicates include the value column");
                return null;
            }

            // check grouping
            if (checkGroupings(context, scan)) {
                return null;
            }

            // check aggregation function
            if (checkAggregations(context, scan)) {
                return null;
            }

            scan.setPreAggregation(true);
            scan.setTurnOffReason("");
            return null;
        }

        private boolean checkGroupings(PreAggregationContext context, PhysicalOlapScanOperator scan) {
            Map<ColumnRefOperator, Column> refColumnMap = scan.getColumnRefMap();

            List<ColumnRefOperator> groups = Lists.newArrayList();
            context.groupings.stream().map(Utils::extractColumnRef).forEach(groups::addAll);

            if (groups.stream().anyMatch(d -> !refColumnMap.containsKey(d))) {
                scan.setTurnOffReason("Group columns isn't bound table " + scan.getTable().getName());
                return true;
            }

            if (groups.stream().anyMatch(d -> !refColumnMap.get(d).isKey())) {
                scan.setTurnOffReason("Group columns isn't Key column");
                return true;
            }
            return false;
        }

        private boolean checkAggregations(PreAggregationContext context, PhysicalOlapScanOperator scan) {
            Map<ColumnRefOperator, Column> refColumnMap = scan.getColumnRefMap();

            for (final ScalarOperator so : context.aggregations) {
                Preconditions.checkState(OperatorType.CALL.equals(so.getOpType()));

                CallOperator call = (CallOperator) so;
                if (call.getChildren().size() != 1) {
                    scan.setTurnOffReason("Aggregate function has more than one parameter");
                    return true;
                }

                ScalarOperator child = call.getChild(0);

                List<ColumnRefOperator> returns = Lists.newArrayList();
                List<ColumnRefOperator> conditions = Lists.newArrayList();

                if (OperatorType.VARIABLE.equals(child.getOpType())) {
                    returns.add((ColumnRefOperator) child);
                } else if (child instanceof CastOperator
                        && OperatorType.VARIABLE.equals(child.getChild(0).getOpType())) {
                    if (child.getType().isNumericType() && child.getChild(0).getType().isNumericType()) {
                        returns.add((ColumnRefOperator) child.getChild(0));
                    } else {
                        scan.setTurnOffReason("The parameter of aggregate function isn't numeric type");
                        return true;
                    }
                } else if (call.getChild(0) instanceof CaseWhenOperator) {
                    CaseWhenOperator cwo = (CaseWhenOperator) call.getChild(0);

                    for (int i = 0; i < cwo.getWhenClauseSize(); i++) {
                        if (!OperatorType.VARIABLE.equals(cwo.getThenClause(i).getOpType())) {
                            scan.setTurnOffReason("The result of THEN isn't value column");
                            return true;
                        }

                        conditions.addAll(Utils.extractColumnRef(cwo.getWhenClause(i)));
                        returns.add((ColumnRefOperator) cwo.getThenClause(i));
                    }

                    if (cwo.hasCase()) {
                        conditions.addAll(Utils.extractColumnRef(cwo.getCaseClause()));
                    }

                    if (cwo.hasElse()) {
                        if (OperatorType.VARIABLE.equals(cwo.getElseClause().getOpType())) {
                            returns.add((ColumnRefOperator) cwo.getElseClause());
                        } else if (OperatorType.CONSTANT.equals(cwo.getElseClause().getOpType())
                                && ((ConstantOperator) cwo.getElseClause()).isNull()) {
                            // NULL don't effect result, can open PreAggregate
                        } else {
                            scan.setTurnOffReason("The result of ELSE isn't value column");
                            return true;
                        }
                    }
                } else {
                    scan.setTurnOffReason(
                            "The parameter of aggregate function isn't value column or CAST/CASE-WHEN expression");
                    return true;
                }

                // check conditions
                if (conditions.stream().anyMatch(d -> !refColumnMap.containsKey(d))) {
                    scan.setTurnOffReason("The column of aggregate function isn't bound " + scan.getTable().getName());
                    return true;
                }

                if (conditions.stream().anyMatch(d -> !refColumnMap.get(d).isKey())) {
                    scan.setTurnOffReason("The column of aggregate function isn't key");
                    return true;
                }

                // check returns
                for (ColumnRefOperator ref : returns) {
                    if (!refColumnMap.containsKey(ref)) {
                        scan.setTurnOffReason(
                                "The column of aggregate function isn't bound " + scan.getTable().getName());
                        return true;
                    }

                    Column column = refColumnMap.get(ref);
                    // key column
                    if (column.isKey()) {
                        if (!"MAX|MIN".contains(call.getFnName().toUpperCase()) &&
                                !AGGREGATE_ONLY_KEY.contains(call.getFnName().toUpperCase())) {
                            scan.setTurnOffReason("The key column don't support aggregate function: "
                                    + call.getFnName().toUpperCase());
                            return true;
                        }
                        continue;
                    }

                    // value column
                    if ("HLL_UNION_AGG|HLL_RAW_AGG".contains(call.getFnName().toUpperCase())) {
                        // skip
                    } else if (AGGREGATE_ONLY_KEY.contains(call.getFnName().toUpperCase())) {
                        scan.setTurnOffReason(
                                "Aggregation function " + call.getFnName().toUpperCase() + " just work on key column");
                        return true;
                    } else if ((FunctionSet.BITMAP_UNION.equalsIgnoreCase(call.getFnName())
                            || FunctionSet.BITMAP_UNION_COUNT.equalsIgnoreCase(call.getFnName()))) {
                        if (!AggregateType.BITMAP_UNION.equals(column.getAggregationType())) {
                            scan.setTurnOffReason(
                                    "Aggregate Operator not match: BITMAP_UNION <--> " + column.getAggregationType());
                        }
                    } else if (!call.getFnName().equalsIgnoreCase(column.getAggregationType().name())) {
                        scan.setTurnOffReason(
                                "Aggregate Operator not match: " + call.getFnName().toUpperCase() + " <--> " + column
                                        .getAggregationType().name().toUpperCase());
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public Void visitPhysicalHashJoin(OptExpression optExpression, PreAggregationContext context) {
            context.hasJoin = true;
            context.groupings.clear();
            context.aggregations.clear();
            return visit(optExpression, context);
        }
    }

    public static class PreAggregationContext implements Cloneable {
        public boolean hasJoin = false;
        public List<ScalarOperator> aggregations = Lists.newArrayList();
        public List<ScalarOperator> groupings = Lists.newArrayList();

        @Override
        public PreAggregationContext clone() {
            try {
                PreAggregationContext context = (PreAggregationContext) super.clone();
                // Just shallow copy
                context.aggregations = Lists.newArrayList(aggregations);
                context.groupings = Lists.newArrayList(groupings);
                return context;
            } catch (CloneNotSupportedException ignored) {
            }

            return null;
        }
    }
}
