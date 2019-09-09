package com.hibiup.jbpm

import org.scalatest.FlatSpec
import Example_3_camunda_fluent_api._
import Example_1_Evaluation._
import cats.effect.IO
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.kie.test.util.db.PersistenceUtil

import scala.concurrent.ExecutionContext

class Example_3_camunda_fluent_api_Test extends FlatSpec {
    "Camunda Fluent API" should "" in {
        /** Create process and add tasks */
        _testCreateFlow

        /** Enum process tasks */
        import scala.jdk.CollectionConverters._
        implicit val cs = IO.contextShift(ExecutionContext.global)
        read.map{model =>
            model.getDefinitions.getRootElements.asScala.foreach{element =>
                println(element.getId)
            }

            // Get start node
            Option(model.getModelElementsByType[StartEvent](classOf[StartEvent]).asScala.head) match {
                case Some(startNode:StartEvent) => {
                    println(startNode.getId)
                    // Call to enumeration
                    enum((flow, task) => println(s"""Flow: ${flow.getId} => Task: ${task.getId}"""), startNode.getOutgoing.iterator)
                }
            }
        }.run("src/main/resources/flows/Example_3_camunda_fluent_api.bpmn").unsafeRunSync().unsafeRunSync()
    
    
        _tesInsertNodet

        /*import scala.jdk.CollectionConverters._
        // Use jBPM to test run
        PersistenceUtil.setupPoolingDataSource()
        init.map{ case (m, e) =>
            createSession.map(session => {
                    start(e, "Test_Process", params = Map.empty[String, AnyRef].asJava).map(
                        taskService => {
                            val taskSummaries = taskService.getTasksAssignedAsBusinessAdministrator("Administrator", "en_US")
                            for( taskSummary <- taskSummaries.asScala) {
                                taskService.start(taskSummary.getId, "Administrator")  // 必须是合法用户（owner 或 admin）并且具有权限。参考 Example_1_Evaluation
                                taskService.complete(taskSummary.getId, "Administrator", null)
                            }
                        }).map(_ =>
                        closeSession.run(session)
                    )
                    }.map(_ =>
                destroyEngine(m)
            ).run(session)).run(e)}.run("flows/Example_3_camunda_fluent_api.bpmn")
                .unsafeRunSync()  // 执行 createSession 乃至结束
                .unsafeRunSync()  // 执行 init
                .unsafeRunSync()*/
    }
}
