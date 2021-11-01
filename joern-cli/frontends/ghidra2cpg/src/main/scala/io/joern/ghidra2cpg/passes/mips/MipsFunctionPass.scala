package io.joern.ghidra2cpg.passes.mips

import scala.jdk.CollectionConverters._
import scala.language.implicitConversions
import ghidra.app.decompiler.DecompInterface
import ghidra.program.model.address.GenericAddress
import ghidra.program.model.lang.Register
import ghidra.program.model.listing.{Function, Instruction, Program}
import ghidra.program.model.scalar.Scalar
import ghidra.util.task.ConsoleTaskMonitor
import io.joern.ghidra2cpg.Types
import io.joern.ghidra2cpg.passes.FunctionPass
import io.joern.ghidra2cpg.processors.Processor
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.NewCall
import io.shiftleft.codepropertygraph.generated.{EdgeTypes, nodes}
import io.shiftleft.passes.{DiffGraph, IntervalKeyPool}

import scala.language.implicitConversions

class MipsFunctionPass(processor: Processor,
                       currentProgram: Program,
                       filename: String,
                       functions: List[Function],
                       function: Function,
                       cpg: Cpg,
                       keyPool: IntervalKeyPool,
                       decompInterface: DecompInterface)
    extends FunctionPass(processor, currentProgram, filename, functions, function, cpg, keyPool, decompInterface) {

  val mipsCallInstructions = List("jalr", "jal")

  // Iterating over operands and add edges to call
  override def handleArguments(
                       instruction: Instruction,
                       callNode: NewCall
                     ): Unit = {
    val mnemonicString = instruction.getMnemonicString
    if (mipsCallInstructions.contains(mnemonicString)) {
      val mipsPrefix = "^t9=>".r
      val calledFunction =
        mipsPrefix.replaceFirstIn(codeUnitFormat.getOperandRepresentationString(instruction, 0), "")
      val callee = functions.find(function => function.getName().equals(calledFunction))
      if (callee.nonEmpty) {
        // Array of tuples containing (checked parameter name, parameter index, parameter data type)
        var checkedParameters: Array[(String, Int, String)] = Array.empty

        if (callee.head.isThunk) {
          // thunk functions contain parameters already
          val parameters = callee.head.getParameters

          checkedParameters = parameters.map { parameter =>
            val checkedParameter =
              if (parameter.getRegister == null) parameter.getName
              else parameter.getRegister.getName

            // checked parameter name, parameter index, parameter data type
            (checkedParameter, parameter.getOrdinal + 1, parameter.getDataType.getName)
          }
        } else {
          // non thunk functions do not contain function parameters by default
          // need to decompile function to get parameter information
          // decompilation for a function is cached so subsequent calls to decompile should be free
          val parameters = decompInterface
            .decompileFunction(callee.head, 60, new ConsoleTaskMonitor())
            .getHighFunction
            .getLocalSymbolMap
            .getSymbols
            .asScala
            .toSeq
            .filter(_.isParameter)
            .toArray

          checkedParameters = parameters.map { parameter =>
            val checkedParameter =
              if (parameter.getStorage.getRegister == null) parameter.getName
              else parameter.getStorage.getRegister.getName

            // checked parameter name, parameter index, parameter data type
            (checkedParameter, parameter.getCategoryIndex + 1, parameter.getDataType.getName)
          }
        }

        checkedParameters.foreach {
          case (checkedParameter, index, dataType) =>
            val node = nodes
              .NewIdentifier()
              .code(checkedParameter)
              .name(checkedParameter) //parameter.getName)
              .order(index)
              .argumentIndex(index)
              .typeFullName(Types.registerType(dataType))
              .lineNumber(Some(instruction.getMinAddress.getOffsetAsBigInteger.intValue))
            diffGraph.addNode(node)
            diffGraph.addEdge(callNode, node, EdgeTypes.ARGUMENT)
            diffGraph.addEdge(callNode, node, EdgeTypes.AST)
        }
      }
    } else {
      for (index <- 0 until instruction.getNumOperands) {
        val opObjects = instruction.getOpObjects(index)
        if (opObjects.length > 1) {
          val argument = String.valueOf(
            instruction.getDefaultOperandRepresentation(index)
          )
          val node = nodes
            .NewIdentifier()
            .code(argument)
            .name(argument)
            .order(index + 1)
            .argumentIndex(index + 1)
            .typeFullName(Types.registerType(argument))
            .lineNumber(Some(instruction.getMinAddress.getOffsetAsBigInteger.intValue))
          diffGraph.addNode(node)
          diffGraph.addEdge(callNode, node, EdgeTypes.ARGUMENT)
          diffGraph.addEdge(callNode, node, EdgeTypes.AST)
        } else
          for (opObject <- opObjects) { //
            val className = opObject.getClass.getSimpleName
            opObject.getClass.getSimpleName match {
              case "Register" =>
                val register = opObject.asInstanceOf[Register]
                val node = nodes
                  .NewIdentifier()
                  .code(register.getName)
                  .name(register.getName)
                  .order(index + 1)
                  .argumentIndex(index + 1)
                  .typeFullName(Types.registerType(register.getName))
                  .lineNumber(Some(instruction.getMinAddress.getOffsetAsBigInteger.intValue))
                diffGraph.addNode(node)
                diffGraph.addEdge(callNode, node, EdgeTypes.ARGUMENT)
                diffGraph.addEdge(callNode, node, EdgeTypes.AST)
              case "Scalar" =>
                val scalar =
                  opObject.asInstanceOf[Scalar].toString(16, false, false, "", "")
                val node = nodes
                  .NewLiteral()
                  .code(scalar)
                  .order(index + 1)
                  .argumentIndex(index + 1)
                  .typeFullName(scalar)
                  .lineNumber(Some(instruction.getMinAddress.getOffsetAsBigInteger.intValue))
                diffGraph.addNode(node)
                diffGraph.addEdge(callNode, node, EdgeTypes.ARGUMENT)
                diffGraph.addEdge(callNode, node, EdgeTypes.AST)
              case "GenericAddress" =>
                // TODO: try to resolve the address
                val genericAddress =
                  opObject.asInstanceOf[GenericAddress].toString()
                val node = nodes
                  .NewLiteral()
                  .code(genericAddress)
                  .order(index + 1)
                  .argumentIndex(index + 1)
                  .typeFullName(genericAddress)
                  .lineNumber(Some(instruction.getMinAddress.getOffsetAsBigInteger.intValue))
                diffGraph.addNode(node)
                diffGraph.addEdge(callNode, node, EdgeTypes.ARGUMENT)
                diffGraph.addEdge(callNode, node, EdgeTypes.AST)
              case _ =>
                println(
                  s"""Unsupported argument: $opObject $className"""
                )
            }
          }
      }
    }
  }
  override def runOnPart(part: String): Iterator[DiffGraph] = {
    createMethodNode()
    handleParameters()
    handleLocals()
    handleBody()
    Iterator(diffGraph.build())
  }
}
