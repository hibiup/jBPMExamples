package com.hibiup.jbpm

import java.io.{File, FileInputStream}
import java.util

import cats.Applicative
import cats._
import cats.data.{IndexedStateT, Kleisli, OptionT, StateT}
import cats.effect.{ContextShift, IO}
import org.camunda.bpm.model.bpmn.{Bpmn, BpmnModelInstance}

import scala.concurrent.ExecutionContext.global
import cats.implicits._
import org.camunda.bpm.model.bpmn.instance.{BpmnModelElementInstance, Definitions, EndEvent, FlowNode, PotentialOwner, Process, SequenceFlow, StartEvent, UserTask}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

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

    private def removeElement(name:String, model: BpmnModelInstance):Unit = {
        if(model.getModelElementsByType[Process](classOf[Process]).size > 0)
            model.getModelElementsByType[Process](classOf[Process]).forEach(process =>
                Option(model.getModelElementById[BpmnModelElementInstance](name)) match {
                        case Some(element) =>
                            process.removeChildElement(element)
                } )
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

    def newUserTask(taskId:String):StateT[IO, BpmnModelInstance, Option[UserTask]] = StateT{ model => IO{
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

    def createFlow(from:String, to:String):StateT[IO, BpmnModelInstance, Option[SequenceFlow]] = StateT{ model => IO{
        (model,
            if (model.getModelElementsByType[Process](classOf[Process]).size > 0)
                model.getModelElementsByType[Process](classOf[Process]).asScala.head match {
                    case process: BpmnModelElementInstance =>
                        val flow = createElement[SequenceFlow](process, _.setId(s"""$from-$to"""), model)
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

    def insertFront(name:String, toFront:String): StateT[IO, BpmnModelInstance, Unit] =
        for{
            newTask <- newUserTask(name)
            // Update flow to connect to the new task
            _ <- StateT[IO, BpmnModelInstance, Option[Unit]]{ model => IO{
                (newTask, Option(model.getModelElementById[UserTask](toFront))) match {
                    case (Some(t1), Some(t2:UserTask)) =>
                        t1.getIncoming.clear()
                        t2.getIncoming.asScala.foreach{ flow =>
                            flow.setId(flow.getId.replace(s"-${t2.getId}", s"-${t1.getId}"))
                            flow.setTarget(t1)
                            t1.getIncoming.add(flow)
                        }
                        t2.getIncoming.clear()
                        (model, Option())
                    case _ => (model, None)
                }
            }}
            // Add new flow from new task to the old one
            p <- createFlow(name, toFront)
        } yield()

    def moveToFront(name:String, toFront:String):StateT[IO, BpmnModelInstance, Unit] = StateT{ model =>
        (Option(model.getModelElementById[UserTask](name)), Option(model.getModelElementById[UserTask](toFront))) match {
            case (Some(t1), Some(t2)) =>
                // Opt out
                t1.getIncoming.forEach(upStream => {
                    t1.getOutgoing.forEach(downStream => {
                        // Connect upStream to downStream
                        createFlow(upStream.getSource.getId, downStream.getTarget.getId).run(model).unsafeRunSync()
                        downStream.getTarget.getIncoming.remove(downStream)
                        removeElement(downStream.getId, model)
                    })
                    // Remove old downStream
                    t1.getOutgoing.clear()

                    // Remove old upStream
                    upStream.getSource.getOutgoing.remove(upStream)
                    removeElement(upStream.getId, model)
                })
                t1.getIncoming.clear()

                // Opt in
                t2.getIncoming.forEach(upStream => {
                    createFlow(upStream.getSource.getId, t1.getId).run(model).unsafeRunSync()
                    upStream.getSource.getOutgoing.remove(upStream)
                    removeElement(upStream.getId, model)
                })
                t2.getIncoming.clear()

                createFlow(t1.getId, t2.getId).map(_=>()).run(model)
            case _ => IO((model,()))
        }
    }

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
     * Test functions
     * */
    def _testCreateFlow = {
        /** Creation */
        def testFlow:StateT[IO, BpmnModelInstance, Unit] = StateT{ model =>
            (for{
                _ <- newProcess("Test_Process")
                startNode <- addStartNode
                endNode <- addEndNode
                _ <- newUserTask("UserTask1")
                _ <- newUserTask("UserTask2")
                _ <- newUserTask("UserTask3")
                _ <- createFlow("Start", "UserTask1")
                _ <- createFlow("UserTask1", "UserTask2")
                _ <- createFlow("UserTask2", "UserTask3")
                _ <- createFlow("UserTask3", "End")
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

    def _testInsertNode = {
        implicit val cs = IO.contextShift(ExecutionContext.global)

        read.map { model =>
            insertFront("NewTask", "UserTask3").run(model).map{
                case (m, _) => toFile("src/main/resources/flows/Example_3_camunda_fluent_api_inserted.bpmn").run(m)
            }
        }.run("src/main/resources/flows/Example_3_camunda_fluent_api.bpmn").unsafeRunSync().unsafeRunSync().unsafeRunSync()
    }

    def _testMoveNode = {
        implicit val cs = IO.contextShift(ExecutionContext.global)

        read.map { model =>
            moveToFront("UserTask3", "UserTask1").run(model).map{
                case (m, _) => toFile("src/main/resources/flows/Example_3_camunda_fluent_api_moved.bpmn").run(m)
            }
        }.run("src/main/resources/flows/Example_3_camunda_fluent_api.bpmn").unsafeRunSync().unsafeRunSync().unsafeRunSync()
    }
}
