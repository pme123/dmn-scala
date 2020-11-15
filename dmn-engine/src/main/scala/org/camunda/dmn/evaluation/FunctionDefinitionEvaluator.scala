package org.camunda.dmn.evaluation

import org.camunda.dmn.DmnEngine._
import org.camunda.dmn.FunctionalHelper._
import org.camunda.dmn.parser.ParsedFunctionDefinition
import org.camunda.feel.ParsedExpression
import org.camunda.feel.interpreter.{Val, ValError, ValFunction}

class FunctionDefinitionEvaluator(
    eval: (ParsedExpression, EvalContext) => Either[Failure, Val]) {

  def eval(function: ParsedFunctionDefinition,
           context: EvalContext): Either[Failure, Val] = {
    Right(createFunction(function.expression, function.parameters, context))
  }

  private def createFunction(expression: ParsedExpression,
                             parameters: Iterable[(String, String)],
                             context: EvalContext): ValFunction = {
    val parameterNames = parameters.map(_._1).toList

    ValFunction(
      params = parameterNames,
      invoke = args => {
        val result = validateArguments(parameters, args, context).flatMap(
          arguments =>
            eval(expression,
                 context.copy(variables = context.variables ++ arguments)))

        result match {
          case Right(value)  => value
          case Left(failure) => ValError(failure.message)
        }
      }
    )
  }

  private def validateArguments(
      parameters: Iterable[(String, String)],
      args: List[Val],
      context: EvalContext): Either[Failure, List[(String, Val)]] = {
    mapEither[((String, String), Val), (String, Val)](parameters.zip(args), {
      case ((name, typeRef), arg) =>
        TypeChecker
          .isOfType(arg, typeRef)
          .map(name -> _)
    })
  }

}
