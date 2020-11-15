package org.camunda.dmn.evaluation

import org.camunda.dmn.DmnEngine._
import org.camunda.dmn.FunctionalHelper._
import org.camunda.dmn.parser.{ParsedBusinessKnowledgeModel, ParsedDecisionLogic}
import org.camunda.feel.interpreter.{Val, ValError, ValFunction, ValueMapper}

class BusinessKnowledgeEvaluator(
    eval: (ParsedDecisionLogic, EvalContext) => Either[Failure, Val],
    valueMapper: ValueMapper) {

  def eval(bkm: ParsedBusinessKnowledgeModel,
           context: EvalContext): Either[Failure, Val] = {

    evalRequiredKnowledge(bkm.requiredBkms, context)
      .flatMap(functions => {

        val evalContext =
          context.copy(variables = context.variables ++ functions,
                       currentElement = bkm)

        validateParameters(bkm.parameters, evalContext)
          .flatMap(_ => eval(bkm.logic, evalContext))
      })
  }

  def createFunction(
      bkm: ParsedBusinessKnowledgeModel,
      context: EvalContext): Either[Failure, (String, ValFunction)] = {

    evalRequiredKnowledge(bkm.requiredBkms, context).map(functions => {

      val evalContext = context.copy(variables = context.variables ++ functions,
                                     currentElement = bkm)

      val function = createFunction(bkm.logic, bkm.parameters, evalContext)

      bkm.name -> function
    })
  }

  private def evalRequiredKnowledge(
      knowledgeRequirements: Iterable[ParsedBusinessKnowledgeModel],
      context: EvalContext): Either[Failure, List[(String, ValFunction)]] = {
    mapEither(
      knowledgeRequirements,
      (bkm: ParsedBusinessKnowledgeModel) => createFunction(bkm, context))
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

  private def validateParameters(
      parameters: Iterable[(String, String)],
      context: EvalContext): Either[Failure, List[Any]] = {
    mapEither[(String, String), Any](
      parameters, {
        case (name, typeRef) =>
          context.variables
            .get(name)
            .map(v => TypeChecker.isOfType(valueMapper.toVal(v), typeRef))
            .getOrElse(Left(Failure(s"no parameter found with name '$name'")))
      }
    )
  }

  private def createFunction(expression: ParsedDecisionLogic,
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

}
