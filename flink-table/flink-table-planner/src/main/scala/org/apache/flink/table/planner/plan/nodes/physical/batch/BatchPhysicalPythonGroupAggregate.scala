/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.plan.nodes.physical.batch

import org.apache.flink.table.functions.UserDefinedFunction
import org.apache.flink.table.planner.calcite.FlinkTypeFactory
import org.apache.flink.table.planner.plan.`trait`.{FlinkRelDistribution, FlinkRelDistributionTraitDef}
import org.apache.flink.table.planner.plan.nodes.exec.{ExecNode, InputProperty}
import org.apache.flink.table.planner.plan.nodes.exec.batch.BatchExecPythonGroupAggregate
import org.apache.flink.table.planner.plan.rules.physical.batch.BatchPhysicalJoinRuleBase
import org.apache.flink.table.planner.plan.utils.{FlinkRelOptUtil, RelExplainUtil}

import org.apache.calcite.plan.{RelOptCluster, RelOptRule, RelTraitSet}
import org.apache.calcite.rel.RelDistribution.Type.{HASH_DISTRIBUTED, SINGLETON}
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.rel.{RelCollationTraitDef, RelCollations, RelNode, RelWriter}
import org.apache.calcite.util.{ImmutableIntList, Util}

import java.util

import scala.collection.JavaConversions._

/**
 * Batch physical RelNode for aggregate (Python user defined aggregate function).
 */
class BatchPhysicalPythonGroupAggregate(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    inputRel: RelNode,
    outputRowType: RelDataType,
    inputRowType: RelDataType,
    val aggInputRowType: RelDataType,
    grouping: Array[Int],
    auxGrouping: Array[Int],
    aggCalls: Seq[AggregateCall],
    aggFunctions: Array[UserDefinedFunction])
  extends BatchPhysicalGroupAggregateBase(
    cluster,
    traitSet,
    inputRel,
    outputRowType,
    grouping,
    auxGrouping,
    aggCalls.zip(aggFunctions),
    isMerge = false,
    isFinal = true) {

  override def explainTerms(pw: RelWriter): RelWriter =
    super.explainTerms(pw)
      .itemIf("groupBy",
              RelExplainUtil.fieldToString(grouping, inputRowType), grouping.nonEmpty)
      .itemIf("auxGrouping",
              RelExplainUtil.fieldToString(auxGrouping, inputRowType), auxGrouping.nonEmpty)
      .item("select", RelExplainUtil.groupAggregationToString(
        inputRowType,
        outputRowType,
        grouping,
        auxGrouping,
        aggCalls.zip(aggFunctions),
        isMerge = false,
        isGlobal = true))

  override def satisfyTraits(requiredTraitSet: RelTraitSet): Option[RelNode] = {
    val requiredDistribution = requiredTraitSet.getTrait(FlinkRelDistributionTraitDef.INSTANCE)
    val canSatisfy = requiredDistribution.getType match {
      case SINGLETON => grouping.length == 0
      case HASH_DISTRIBUTED =>
        val shuffleKeys = requiredDistribution.getKeys
        val groupKeysList = ImmutableIntList.of(grouping.indices.toArray: _*)
        if (requiredDistribution.requireStrict) {
          shuffleKeys == groupKeysList
        } else if (Util.startsWith(shuffleKeys, groupKeysList)) {
          // If required distribution is not strict, Hash[a] can satisfy Hash[a, b].
          // so return true if shuffleKeys(Hash[a, b]) start with groupKeys(Hash[a])
          true
        } else {
          // If partialKey is enabled, try to use partial key to satisfy the required distribution
          val tableConfig = FlinkRelOptUtil.getTableConfigFromContext(this)
          val partialKeyEnabled = tableConfig.get(
            BatchPhysicalJoinRuleBase.TABLE_OPTIMIZER_SHUFFLE_BY_PARTIAL_KEY_ENABLED)
          partialKeyEnabled && groupKeysList.containsAll(shuffleKeys)
        }
      case _ => false
    }
    if (!canSatisfy) {
      return None
    }

    val inputRequiredDistribution = requiredDistribution.getType match {
      case SINGLETON => requiredDistribution
      case HASH_DISTRIBUTED =>
        val shuffleKeys = requiredDistribution.getKeys
        val groupKeysList = ImmutableIntList.of(grouping.indices.toArray: _*)
        if (requiredDistribution.requireStrict) {
          FlinkRelDistribution.hash(grouping, requireStrict = true)
        } else if (Util.startsWith(shuffleKeys, groupKeysList)) {
          // Hash [a] can satisfy Hash[a, b]
          FlinkRelDistribution.hash(grouping, requireStrict = false)
        } else {
          // use partial key to satisfy the required distribution
          FlinkRelDistribution.hash(shuffleKeys.map(grouping(_)).toArray, requireStrict = false)
        }
    }

    val providedCollation = if (grouping.length == 0) {
      RelCollations.EMPTY
    } else {
      val providedFieldCollations = grouping.map(FlinkRelOptUtil.ofRelFieldCollation).toList
      RelCollations.of(providedFieldCollations)
    }
    val requiredCollation = requiredTraitSet.getTrait(RelCollationTraitDef.INSTANCE)
    val newProvidedTraitSet = if (providedCollation.satisfies(requiredCollation)) {
      getTraitSet.replace(requiredDistribution).replace(requiredCollation)
    } else {
      getTraitSet.replace(requiredDistribution)
    }
    val newInput = RelOptRule.convert(getInput, inputRequiredDistribution)
    Some(copy(newProvidedTraitSet, Seq(newInput)))
  }

  override def copy(traitSet: RelTraitSet, inputs: util.List[RelNode]): RelNode = {
    new BatchPhysicalPythonGroupAggregate(
      cluster,
      traitSet,
      inputs.get(0),
      outputRowType,
      inputRowType,
      aggInputRowType,
      grouping,
      auxGrouping,
      aggCalls,
      aggFunctions)
  }

  override def translateToExecNode(): ExecNode[_] = {
    val requiredDistribution = if (grouping.length == 0) {
      InputProperty.SINGLETON_DISTRIBUTION
    } else {
      InputProperty.hashDistribution(grouping)
    }
    new BatchExecPythonGroupAggregate(
      grouping,
      grouping ++ auxGrouping,
      aggCalls.toArray,
      InputProperty.builder()
        .requiredDistribution(requiredDistribution)
        .damBehavior(InputProperty.DamBehavior.END_INPUT)
        .build(),
      FlinkTypeFactory.toLogicalRowType(getRowType),
      getRelDetailedDescription
    )
  }
}

