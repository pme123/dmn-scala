package org.camunda.dmn.evaluation

import org.camunda.bpm.model.dmn._
import org.camunda.dmn.Audit.{DecisionTableEvaluationResult, EvaluatedInput, EvaluatedOutput, EvaluatedRule}
import org.camunda.dmn.DmnEngine._
import org.camunda.dmn.FunctionalHelper._
import org.camunda.dmn.parser.{ParsedDecisionTable, ParsedInput, ParsedOutput, ParsedRule}
import org.camunda.feel._
import org.camunda.feel.interpreter.Context.StaticContext
import org.camunda.feel.interpreter.{EvalContext => _, _}

class DecisionTableEvaluator(
                              eval: (ParsedExpression, EvalContext) => Either[Failure, Val]) {

  def eval(decisionTable: ParsedDecisionTable,
           context: EvalContext): Either[Failure, Val] = {
    implicit val ctx: EvalContext = context

    evalInputExpressions(decisionTable.inputs) match {
      case Left(failure) =>
        audit(decisionTable, Nil, Nil, ValError(failure.message))
        Left(failure)
      case Right(inputValues) =>
        (checkRules(decisionTable.rules, inputValues) match {
          case Left(failure) =>
            audit(decisionTable, inputValues, Nil, ValError(failure.message))
            Left(failure)
          case right =>
            right
        })
          .map(_.flatten)
          .flatMap {
            case Nil =>
              applyDefaultOutputEntries(decisionTable.outputs) match {
                case r@Right(result) =>
                  audit(decisionTable, inputValues, Nil, result)
                  r
                case l@Left(failure) =>
                  audit(decisionTable, inputValues, Nil, ValError(failure.message))
                  l
              }
            case rules if decisionTable.hitPolicy == HitPolicy.FIRST =>
              val firstMatchedRule = List(rules.head)

              evalOutputValues(firstMatchedRule).flatMap { values =>
                applyHitPolicy(decisionTable, values.map(_.toMap)) match {
                  case r@Right(result) =>
                    audit(decisionTable,
                      inputValues,
                      firstMatchedRule.zip(values),
                      result)
                    r
                  case l@Left(failure) =>
                    audit(decisionTable, inputValues, rules.zip(values), ValError(failure.message))
                    l
                }
              }
            case rules =>
              evalOutputValues(rules).flatMap { values =>
                applyHitPolicy(decisionTable, values.map(_.toMap)) match {
                  case r@Right(result) =>
                    audit(decisionTable, inputValues, rules.zip(values), result)
                    r
                  case l@Left(failure) =>
                    audit(decisionTable, inputValues, rules.zip(values), ValError(failure.message))
                    l
                }
              }
          }
    }
  }

  private def evalInputExpressions(inputs: Iterable[ParsedInput])(
    implicit context: EvalContext): Either[Failure, List[Val]] = {
    mapEither(inputs, (input: ParsedInput) => eval(input.expression, context))
  }

  private def checkRules(rules: Iterable[ParsedRule], inputValues: List[Val])(
    implicit context: EvalContext): Either[Failure, List[Option[ParsedRule]]] = {
    mapEither(rules, (rule: ParsedRule) => {

      evalInputEntries(rule.inputEntries.zip(inputValues))
        .map(isMet => if (isMet) Some(rule) else None)
    })
  }

  private def evalInputEntries(inputEntries: Iterable[(ParsedExpression, Val)])(
    implicit
    context: EvalContext): Either[Failure, Boolean] = {

    inputEntries.toList match {
      case Nil => Right(true)
      case (entry, value) :: is =>

        evalInputEntry(entry, value)
          .flatMap {
            case ValBoolean(false) => Right(false)
            case ValBoolean(true) => evalInputEntries(is)
            case other =>
              Left(Failure(
                s"input entry must return true or false, but found '$other'"))
          }
    }
  }

  private def evalInputEntry(entry: ParsedExpression, inputValue: Val)(
      implicit
      context: EvalContext): Either[Failure, Val] = {

    val variablesWithInput = context.variables + (FeelEngine.UnaryTests.defaultInputVariable -> inputValue)

    eval(entry, context.copy(variables = variablesWithInput))
  }

  private def applyDefaultOutputEntries(outputs: Iterable[ParsedOutput])(
    implicit
    context: EvalContext): Either[Failure, Val] = {

    evalDefaultOutputEntries(outputs)
      .map(_.flatten.toMap)
      .map { outputValues =>
        if (outputValues.isEmpty) {
          ValNull
        } else if (outputValues.size == 1) {
          outputValues.values.head
        } else {
          ValContext(StaticContext(outputValues))
        }
      }
  }

  private def evalDefaultOutputEntries(outputs: Iterable[ParsedOutput])(
    implicit
    context: EvalContext): Either[Failure, List[Option[(String, Val)]]] = {
    mapEither(outputs, (output: ParsedOutput) => {

      output.defaultValue
        .map(expr =>
          eval(expr, context)
            .map(r => Some(output.name -> r)))
        .getOrElse(Right(None))
    })
  }

  private def evalOutputValues(rules: Iterable[ParsedRule])(
    implicit
    context: EvalContext): Either[Failure, List[Iterable[(String, Val)]]] = {

    mapEither(
      rules,
      (rule: ParsedRule) => {

        mapEither[(String, ParsedExpression), (String, Val)](
          rule.outputEntries, {
            case (name, expr) =>
              eval(expr, context)
                .map(name -> _)
          })
      }
    )
  }

  private def applyHitPolicy(
                              decisionTable: ParsedDecisionTable,
                              outputValues: List[Map[String, Val]]): Either[Failure, Val] = {

    Option(decisionTable.hitPolicy).getOrElse(HitPolicy.UNIQUE) match {

      case HitPolicy.FIRST => Right(singleOutputValue(outputValues))

      case HitPolicy.UNIQUE =>

        if (outputValues.isEmpty || outputValues.size == 1) {
          Right(singleOutputValue(outputValues))
        } else {
          Left(Failure(
            s"multiple values aren't allowed for UNIQUE hit policy. found: '$outputValues'"))
        }

      case HitPolicy.ANY =>

        val disinctValues = outputValues.distinct

        if (disinctValues.isEmpty || disinctValues.size == 1) {
          Right(singleOutputValue(outputValues))
        } else {
          Left(Failure(
            s"different values aren't allowed for ANY hit policy. found: '$disinctValues'"))
        }

      case HitPolicy.PRIORITY =>
        Right(
          singleOutputValue(
            sortByPriority(outputValues, decisionTable.outputs)))

      case HitPolicy.OUTPUT_ORDER =>
        Right(
          multipleOutputValues(
            sortByPriority(outputValues, decisionTable.outputs)))

      case HitPolicy.RULE_ORDER => Right(multipleOutputValues(outputValues))

      case HitPolicy.COLLECT =>
        decisionTable.aggregation match {
          case BuiltinAggregator.MIN =>
            singleNumberValues(outputValues).map(r => ValNumber(r.min))
          case BuiltinAggregator.MAX =>
            singleNumberValues(outputValues).map(r => ValNumber(r.max))
          case BuiltinAggregator.SUM =>
            singleNumberValues(outputValues).map(r => ValNumber(r.sum))
          case BuiltinAggregator.COUNT => Right(ValNumber(outputValues.size))
          case _ => Right(multipleOutputValues(outputValues))
        }
    }
  }

  private def singleOutputValue(values: List[Map[String, Val]]): Val = {
    values.headOption
      .map(v =>
        if (v.size == 1) v.values.head else ValContext(StaticContext(v)))
      .getOrElse(ValNull)
  }

  private def multipleOutputValues(values: List[Map[String, Val]]): Val =
    values match {
      case Nil                           => ValNull
      case list if (list.head.size == 1) => ValList(list.map(_.values.head))
      case list                          => ValList(list.map(m => ValContext(StaticContext(m))))
    }

  private def sortByPriority(
                              outputValues: List[Map[String, Val]],
                              outputs: Iterable[ParsedOutput]): List[Map[String, Val]] = {

    val priorities: Iterable[(String, Map[String, Int])] = outputs.map {
      output =>
        val values = output.value
          .map(_.split(",").map(_.trim.replaceAll("[\"']", ""))) // remove string quotes and whitespaces
          .map(_.toList)
          .getOrElse(List())

        output.name -> values.zipWithIndex.toMap
    }

    outputValues.sortBy { values =>
      val valuePriorities = priorities.map {
        case (output, priority) =>
          values(output) match {
            case ValString(value) =>
              priority.get(value).map(_.toString).getOrElse("_")
            case ValNumber(value) =>
              priority.get(value.toString).map(_.toString).getOrElse("_")
            case _ => "_" // priority doesn't work for other types
          }
      }

      valuePriorities.reduce(_ + _)
    }
  }

  private def singleNumberValues(
                                  values: List[Map[String, Val]]): Either[Failure, List[Number]] =
    values match {
      case Nil => Right(Nil)
      case list if list.head.size == 1 =>
        mapEither(list.map(_.values.head), numberValue)
      case list =>
        Left(Failure(s"multiple values aren't allowed. found: $list"))
    }

  private def numberValue(value: Val): Either[Failure, Number] = value match {
    case n: ValNumber => Right(n.value)
    case o => Left(Failure(s"expected number but found '$o'"))
  }

  private def audit(decisionTable: ParsedDecisionTable,
                    inputValues: List[Val],
                    matchedRules: List[(ParsedRule, Iterable[(String, Val)])],
                    result: Val)(implicit context: EvalContext): Val = {

    val evalInputs = decisionTable.inputs
      .zip(inputValues)
      .map { case (input, value) => EvaluatedInput(input, value) }
      .toList

    val evalRules = matchedRules.map {
      case (rule, values) =>
        EvaluatedRule(rule = rule,
          outputs = decisionTable.outputs
            .zip(values)
            .map {
              case (output, (_, value)) =>
                new EvaluatedOutput(output, value)
            }
            .toList)
    }

    val evalResult = DecisionTableEvaluationResult(inputs = evalInputs,
      matchedRules = evalRules,
      result = result)

    context.audit(decisionTable, evalResult)

    result
  }

}
