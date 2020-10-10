package org.camunda.dmn.parser

import org.camunda.bpm.model.dmn.{BuiltinAggregator, DmnModelInstance, HitPolicy}
import org.camunda.feel.ParsedExpression

case class ParsedDmn(model: DmnModelInstance,
                     decisions: Iterable[ParsedDecision]) {

  val decisionsById: Map[String, ParsedDecision] =
    decisions.map(d => d.id -> d).toMap
  val decisionsByName: Map[String, ParsedDecision] =
    decisions.map(d => d.name -> d).toMap
}

sealed trait ParsedDecisionLogicContainer {
  val id: String
  val name: String
  val logic: ParsedDecisionLogic
}

case class ParsedDecision(id: String,
                          name: String,
                          logic: ParsedDecisionLogic,
                          resultName: String,
                          resultType: Option[String],
                          requiredDecisions: Iterable[ParsedDecision],
                          requiredBkms: Iterable[ParsedBusinessKnowledgeModel])
    extends ParsedDecisionLogicContainer

case class ParsedBusinessKnowledgeModel(
    id: String,
    name: String,
    logic: ParsedDecisionLogic,
    parameters: Iterable[(String, String)],
    requiredBkms: Iterable[ParsedBusinessKnowledgeModel])
    extends ParsedDecisionLogicContainer

sealed trait ParsedDecisionLogic

case class ParsedInvocation(bindings: Iterable[(String, ParsedExpression)],
                            invocation: ParsedBusinessKnowledgeModel)
    extends ParsedDecisionLogic

case class ParsedContext(entries: Iterable[(String, ParsedDecisionLogic)],
                         aggregationEntry: Option[ParsedDecisionLogic])
    extends ParsedDecisionLogic

case class ParsedList(entries: Iterable[ParsedDecisionLogic])
    extends ParsedDecisionLogic

case class ParsedRelation(rows: Iterable[ParsedRelationRow])
    extends ParsedDecisionLogic

case class ParsedRelationRow(columns: Iterable[(String, ParsedDecisionLogic)])

case class ParsedLiteralExpression(expression: ParsedExpression)
    extends ParsedDecisionLogic

case class ParsedFunctionDefinition(expression: ParsedExpression,
                                    parameters: Iterable[(String, String)])
    extends ParsedDecisionLogic

case class ParsedDecisionTable(inputs: Iterable[ParsedInput],
                               outputs: Iterable[ParsedOutput],
                               rules: Iterable[ParsedRule],
                               hitPolicy: HitPolicy,
                               aggregation: BuiltinAggregator)
    extends ParsedDecisionLogic

case class ParsedRule(id: String,
                      inputEntries: Iterable[ParsedExpression],
                      outputEntries: Iterable[(String, ParsedExpression)])

case class ParsedInput(id: String, name: String, expression: ParsedExpression)

case class ParsedOutput(id: String,
                        name: String,
                        label: String,
                        value: Option[String],
                        defaultValue: Option[ParsedExpression])
