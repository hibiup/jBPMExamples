package com.hibiup.jbpm

import java.io.{File, FileInputStream}
import java.util

import cats.data.{Kleisli, OptionT, StateT}
import cats.effect.{ContextShift, IO}
import org.camunda.bpm.model.bpmn.{Bpmn, BpmnModelInstance}

import scala.concurrent.ExecutionContext.global
import cats.implicits._
import org.camunda.bpm.model.bpmn.instance.{BpmnModelElementInstance, Definitions, EndEvent, FlowNode, PotentialOwner, Process, SequenceFlow, StartEvent, UserTask}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

object Example_3_camunda_fluent_api {
    def read(implicit cs: ContextShift[IO]):Kleisli[IO, String, BpmnModelInstance] = Kleisli{ path => {
        implicit val cs: ContextShift[IO] = IO.contextShift(global)
        IO.shift *> IO(new FileInputStream(new File(path))).map(Bpmn.readModelFromStream)
    } }

    def newModel: Kleisli[IO, String, BpmnModelInstance] = Kleisli { nameSpace => IO{
        val model = Bpmn.createEmptyModel()
        val definitions = model.newInstance(classOf[Definitions])
        definitions.setTargetNamespace(nameSpace)
        model.setDefinitions(definitions)
        model
    } }

    /**
     * 提示：
     *
     * model.newInstance 函数要求传入确定的类型参数，但是 createElement 只提供泛型类型，我们无法从范型中获得确定的 Class
     * （classOf[T]无法从范型中提取出确定类型），因此必须使用 ClassTag（implicit tag:ClassTag[T] 的语法糖） 来让编译器为
     * 我们提供隐式类型参数，然后通过 classTag[T].runtimeClass 从隐式中取得当前的非范型类型(Class[Any])，然后强制转换成具
     * 有确定类型信息的 Class[T]
     *
     * */
    import scala.reflect.ClassTag
    import scala.reflect._
    private def createElement[T <: BpmnModelElementInstance : ClassTag](parent: BpmnModelElementInstance, setupAttributeValue: T => Unit, model: BpmnModelInstance):T = {
        val element = model.newInstance(classTag[T].runtimeClass.asInstanceOf[Class[T]])
        setupAttributeValue(element)
        parent.addChildElement(element)
        element
    }

    def newProcess(name:String): StateT[IO, BpmnModelInstance, Process] = StateT{ model => IO{
        (model, createElement[Process](model.getDefinitions, p=>p.setId(name), model))
    } }

    def addStartNode:StateT[IO, BpmnModelInstance, Option[StartEvent]] = StateT{model => IO {
        (model, Option{
            if(model.getModelElementsByType[Process](classOf[Process]).size > 0)
                model.getModelElementsByType[Process](classOf[Process]).asScala.head match {
                    case process: BpmnModelElementInstance =>
                        createElement[StartEvent](process, _.setId("Start"), model)
                    case _ => null
                }
        else null
        } )
    } }

    def addEndNode:StateT[IO, BpmnModelInstance, Option[EndEvent]] = StateT{ model => IO{
        (model, Option {
            if (model.getModelElementsByType[Process](classOf[Process]).size > 0)
                model.getModelElementsByType[Process](classOf[Process]).asScala.head match {
                    case process: BpmnModelElementInstance =>
                        createElement[EndEvent](process, _.setId("End"), model)
                    case _ => null
                }
            else null
        } )
    } }

    def addUserTask(taskId:String):StateT[IO, BpmnModelInstance, Option[UserTask]] = StateT{ model => IO{
        (model, Option {
            if (model.getModelElementsByType[Process](classOf[Process]).size > 0)
                model.getModelElementsByType[Process](classOf[Process]).asScala.head match {
                    case process: BpmnModelElementInstance =>
                        createElement[UserTask](process, { task =>
                            task.setId(taskId)
                            task.addChildElement(createElement[PotentialOwner](task, _ =>(), model ) )
                        }, model)
                    case _ => null
                }
            else null
        } )
    } }

    def defineFlow(from:String, to:String, name:String):StateT[IO, BpmnModelInstance, Option[SequenceFlow]] = StateT{ model => IO{
        (model,
            if (model.getModelElementsByType[Process](classOf[Process]).size > 0)
                model.getModelElementsByType[Process](classOf[Process]).asScala.head match {
                    case process: BpmnModelElementInstance =>
                        val flow = createElement[SequenceFlow](process, _.setId(name), model)
                        val optionFrom: Option[FlowNode] = Option(model.getModelElementById(from))
                        val optionTo: Option[FlowNode] = Option(model.getModelElementById(to))
                        (for {
                            f <- optionFrom
                            t <- optionTo
                        } yield (f, t)).map {
                            case (fromTask, toTask) =>
                                flow.setSource(fromTask)
                                fromTask.getOutgoing.add(flow)
                                flow.setTarget(toTask)
                                toTask.getIncoming.add(flow)
                                flow
                        }
                    case _ => None
                }
            else None
        )
    } }

    def validateModel:StateT[IO, BpmnModelInstance, Unit] = StateT { model => IO {
        (model, Bpmn.validateModel(model))
    }}

    def toXml:Kleisli[IO, BpmnModelInstance, String ] = Kleisli {model => IO {
        Bpmn.convertToString(model)
    }}

    def toFile(path:String):Kleisli[IO, BpmnModelInstance, Unit ] = Kleisli {model => IO {
        Bpmn.writeModelToFile(new File(path), model)
    }}

    /** Enumerate loop function */
    def enum(taskCallback: (SequenceFlow, FlowNode) => Unit, sequenceFlow:util.Iterator[SequenceFlow])(implicit cs: ContextShift[IO]): IO[Unit] =
        IO.suspend {
            if(sequenceFlow.hasNext) {
                val flow = sequenceFlow.next
                val task = flow.getTarget
                taskCallback(flow, task)
                enum(taskCallback, task.getOutgoing.iterator())
            }
            else IO.pure(())
        }

    /**
     *
     * */
    def _testCreateFlow = {
        /** Creation */
        def testFlow:StateT[IO, BpmnModelInstance, Unit] = StateT{ model =>
            (for{
                _ <- newProcess("Test_Process")
                startNode <- addStartNode
                endNode <- addEndNode
                _ <- addUserTask("UserTask1")
                _ <- addUserTask("UserTask2")
                _ <- defineFlow("Start", "UserTask1", "From_Start_To_Task1")
                _ <- defineFlow("UserTask1", "UserTask2", "From_Task1_To_Task2")
                _ <- defineFlow("UserTask2", "End", "From_Task2_To_End")
                _ <- validateModel
            } yield ()).run(model)
        }

        newModel.map { model =>
            testFlow.run(model)
        }.map{ io =>
            io.map {
                case (model, _) => model
            }.unsafeRunSync()
        }.andThen(toFile("src/main/resources/flows/Example_3_camunda_fluent_api.bpmn"))
                .run("Camunda Fluent API Test").unsafeRunSync()
    }

    def _testModifyFlow = {
        implicit val cs = IO.contextShift(ExecutionContext.global)
        read.map { model =>
            val a = for{
                flow1 <- Option(model.getModelElementById[SequenceFlow]("From_Start_To_Task1"))
                flow2 <- Option(model.getModelElementById[SequenceFlow]("From_Task1_To_Task2"))
                flow3 <- Option(model.getModelElementById[SequenceFlow]("From_Task2_To_End"))
                task1 <- Option(model.getModelElementById[UserTask]("UserTask1"))
                task2 <- Option(model.getModelElementById[UserTask]("UserTask2"))
            } yield(flow1, flow2, flow3, task1, task2)

            a match {
                case Some((f1, f2, f3, t1, t2)) =>
                    // TODO: Reorganize process flow
            }

            model
        }.andThen(toFile("src/main/resources/flows/Example_3_camunda_fluent_api_modified.bpmn"))
                .run("src/main/resources/flows/Example_3_camunda_fluent_api.bpmn").unsafeRunSync()
    }
}
