package org.camunda.dmn

import org.camunda.feel.interpreter.{Val, ValueMapper}

class NoUnpackValueMapper(valueMapper: ValueMapper) extends ValueMapper {

  override def toVal(x: Any): Val = valueMapper.toVal(x)

  override def unpackVal(value: Val): Any = value

}
