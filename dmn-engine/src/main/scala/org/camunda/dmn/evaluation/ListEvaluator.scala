package org.camunda.dmn.evaluation

import org.camunda.dmn.Audit.SingleEvaluationResult
import org.camunda.dmn.DmnEngine._
import org.camunda.dmn.FunctionalHelper._
import org.camunda.dmn.parser.{ParsedDecisionLogic, ParsedList}
import org.camunda.feel.interpreter.{Val, ValError, ValList}

class ListEvaluator(eval: (ParsedDecisionLogic, EvalContext) => Either[Failure, Val]) {

  def eval(list: ParsedList, context: EvalContext): Either[Failure, Val] = {

    mapEither(list.entries, (expr: ParsedDecisionLogic) => eval(expr, context))
      .map(ValList) match {
      case r@Right(result) =>
        context.audit(list, SingleEvaluationResult(result))
        r
      case l@Left(failure) =>
        context.audit(list, SingleEvaluationResult(ValError(failure.message)))
        l
    }
  }

}
