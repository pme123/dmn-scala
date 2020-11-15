package org.camunda.dmn.evaluation

import org.camunda.dmn.Audit.SingleEvaluationResult
import org.camunda.dmn.DmnEngine._
import org.camunda.dmn.FunctionalHelper._
import org.camunda.dmn.parser.{ParsedDecisionLogic, ParsedRelation, ParsedRelationRow}
import org.camunda.feel.interpreter.Context.StaticContext
import org.camunda.feel.interpreter.{Val, ValContext, ValError, ValList}

class RelationEvaluator(
                         eval: (ParsedDecisionLogic, EvalContext) => Either[Failure, Val]) {

  def eval(relation: ParsedRelation,
           context: EvalContext): Either[Failure, Val] = {

    val rows = mapEither(
      relation.rows,
      (row: ParsedRelationRow) => {
        val columns =
          mapEither[(String, ParsedDecisionLogic), (String, Val)](row.columns, {
            case (column, expr) =>
              eval(expr, context)
                .map(r => column -> r)
          })

        columns.map(values => ValContext(StaticContext(values.toMap)))
      }
    )

    rows.map(ValList) match {
      case r@Right(result) =>
        context.audit(relation, SingleEvaluationResult(result))
        r
      case l@Left(failure) =>
        context.audit(relation, SingleEvaluationResult(ValError(failure.message)))
        l
    }
  }

}
