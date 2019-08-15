package com.hibiup.jbpm

import java.util

import cats.data.{Kleisli, StateT}
import cats.effect.IO
import com.typesafe.scalalogging.Logger
import javax.persistence.Persistence
import org.kie.api.KieServices
import org.kie.api.event.process.{ProcessCompletedEvent, ProcessEventListener, ProcessNodeLeftEvent, ProcessNodeTriggeredEvent, ProcessStartedEvent, ProcessVariableChangedEvent}
import org.kie.api.io.{Resource, ResourceType}
import org.kie.api.runtime.KieSession
import org.kie.api.runtime.manager.{RuntimeEngine, RuntimeEnvironment, RuntimeEnvironmentBuilder, RuntimeManager, RuntimeManagerFactory}
import org.kie.api.task.{TaskService, UserGroupCallback}
import org.kie.api.task.model.TaskSummary
import org.kie.internal.runtime.manager.context.EmptyContext

import scala.jdk.CollectionConverters._
/**
  * 演示如何以最简单地方式载入并执行一个流程。流程所使用案例来自 jBPM 官方案例 Evaluation
  *
  * https://docs.jboss.org/jbpm/release/7.7.0.Final/jbpm-docs/html_single/#jBPMCoreEngine
  *
  * */
object Example_1_Evaluation {
    val logger = Logger(this.getClass)
    /**
      * jBPM 的最基本组件名为 KieBase，该组件全称"knowledge base". 顾名思义它具有管理和执行每一个流程的所有“知识”。它负责解释
      * 每一个流程，客户端通过建立 KieSession 来和它交互。
      *
      * 为了简化 jBPM 的使用，包括如何定义 KieBase 和管理它与每一个客户端的会话（KieSession），jBPM 提供了一个名为 RuntimeManager
      * 的工具。对 jBPM API 的使用一般就从新建 RuntimeManager 开始。
      *
      * 1) 定义环境参数，其中包括要载入的流程定义文件的存储位置。
      * */
    val load:Kleisli[IO, String, Resource] = Kleisli{filePath => IO{
        KieServices.Factory.get.getResources.newClassPathResource(filePath)
    }}
    
    val environment: Kleisli[IO, Resource, RuntimeEnvironment] = Kleisli{ resource => IO{
        val userGroupCallback: UserGroupCallback = new UserGroupCallback {
            private val userGroup = Map(
                "Administrator" -> List("Administrators").asJava,
                "krisv" -> List("users").asJava,
                "john" -> List("managers","users","PM").asJava,
                "mary" -> List("managers","users","HR").asJava
            )
            override def existsUser(userId: String): Boolean = userGroup.contains(userId)
            override def existsGroup(groupId: String): Boolean = userGroup.values.exists(list => list.contains(groupId))
            override def getGroupsForUser(userId: String): util.List[String] = userGroup(userId)
        }

        val emf = Persistence.createEntityManagerFactory("org.jbpm.persistence.jpa")
        val builder = RuntimeEnvironmentBuilder.Factory.get()
            .newDefaultBuilder()

        builder.entityManagerFactory(emf)
            .addAsset(resource, ResourceType.BPMN2)
            .userGroupCallback(userGroupCallback)
            .get()
    } }

    /**
      * 2) 将环境配置传递给 RuntimeManagerFactory 构造出 RuntimeManager。
      *
      * RuntimeManager 管理了 KieBase 和 KieSession 的生命周期。RuntimeManager 对每一个会话有三种管理模式：
      *
      * * Singleton: 所有的流程和客户端都共享同一个 Session。
      * * Per Process: 每一个流程拥有一个 Session，该流程的客户端共享同一个 Session
      * * Per Request: 为每一个请求都分配一个独立的 Session
      *
      * 无论何种模式的会话都是线程安全的。
      * */
    val manager: Kleisli[IO, RuntimeEnvironment, RuntimeManager] = Kleisli{ environment => IO(
        RuntimeManagerFactory.Factory.get().newSingletonRuntimeManager(environment)
    )}

    /**
      * 3) RuntimeManager 将 KieSession 和与之相关的流程（TaskService） 装入一个名为 RuntimeEngine 的组件以供用户使用。
      * */
    val engine: Kleisli[IO, RuntimeManager, (RuntimeManager, RuntimeEngine)] = Kleisli{ manager => IO {
        (manager, manager.getRuntimeEngine(EmptyContext.get()))
    }}

    def init: Kleisli[IO, String, (RuntimeManager, RuntimeEngine)] = load andThen environment andThen manager andThen engine

    /**
      * 4) 从 RuntimeEngine 中获得已经初始化了的 Session
      * */
    val createSession: Kleisli[IO, RuntimeEngine, KieSession] = Kleisli{ engine => IO {
        val session = engine.getKieSession()
        /**
          * 4-1) 为 session 注册事件接收器，比如侦听流程结束事件。
          * */
        session.addEventListener(new ProcessEventListener {
            override def beforeProcessStarted(event: ProcessStartedEvent): Unit = ()
            override def afterProcessStarted(event: ProcessStartedEvent): Unit = ()
            override def beforeProcessCompleted(event: ProcessCompletedEvent): Unit = ()
            override def afterProcessCompleted(event: ProcessCompletedEvent): Unit = {
                val processInstance = event.getProcessInstance
                logger.info("Process completed: processId=["
                    + processInstance.getProcessId() + "]; processInstanceId=["
                    + processInstance.getId() + "]")
            }
            override def beforeNodeTriggered(event: ProcessNodeTriggeredEvent): Unit = ()
            override def afterNodeTriggered(event: ProcessNodeTriggeredEvent): Unit = ()
            override def beforeNodeLeft(event: ProcessNodeLeftEvent): Unit = ()
            override def afterNodeLeft(event: ProcessNodeLeftEvent): Unit = ()
            override def beforeVariableChanged(event: ProcessVariableChangedEvent): Unit = ()
            override def afterVariableChanged(event: ProcessVariableChangedEvent): Unit = ()
        })
        session
    }}

    /**
      * 5) 使用 Session 来启动某个流程。KieBase 中可能管理了许多流程，每一个流程都应定义一个唯一的名称。
      * */
    def start(engine:RuntimeEngine, processId:String, params: util.Map[String, AnyRef]):StateT[IO, KieSession, TaskService] = StateT{ session => IO{
        session.startProcess(processId, params)
        val taskService = engine.getTaskService()
        (session, taskService)
    }}

    /**
      * 6) 销毁会话。
      * */
    def closeSession:StateT[IO, KieSession, Unit] = StateT{ session => IO{
        (session, session.dispose())
    }}

    /**
      * 7) 应用结束，销毁 engine
      * */
    def destroyEngine(manager:RuntimeManager):StateT[IO, RuntimeEngine, Unit] = StateT{engine => IO{
        (engine, manager.disposeRuntimeEngine(engine))
    }}

    // 初始化测试数据库

    /** 8) 将以上初始化步骤组合起来。然后启动某个流程. */
    def _main() = init.map{ case (m, e) =>

        /**
          * 准备启动参数:
          *
          * 在定义流程的同时，可以设置一些环境变量，这些变量可以用来控制流程的Owner，权限、状态、结果、流向等等。在流程的启动、
          * 执行，和关闭的过程中可以修改这些变量。例如我们定义在 userTask (id=1) 的时候指定了 potentialOwner 的等于变量
          * #{employee}, 然后我们在启动流程的时候设置 employee -> krisv，也就等于让 potentialOwner == krisv。
          *
          * 变量 employee 的值必须是有效的，TaskService 通过 UserGroupCallback 来验证用户名和用户身份。并且用户中必须
          * 存在一个 Administrator 用户和 Administrators 组. 参考上面的 RuntimeEnvironmentBuilder.userGroupCallback(userGroupCallback)
          *
          * jbpm-test 包中的 JbpmJUnitBaseTestCase 实现了一个 JBossUserGroupCallbackImpl 实例也可供参考，此例子通过
          * 文本文件 usergroups.properties 作为后端数据录。
          * */
        val params = Map[String, AnyRef](
            "employee" -> "krisv",
            "reason" -> "Yearly performance evaluation"
        )

        createSession.map(session => {
            start(e, "com.sample.evaluation", params = params.asJava).map(
                taskService => {
                    /**
                      * I. 获得 john 当前允许执行的任务。必须得到空，因为 john 的任务在 krisv 完成之后才开始。
                      * */
                    assert(taskService.getTasksAssignedAsPotentialOwner("john", "en-UK").isEmpty)

                    /**
                      * II. 获得 krisv 当前允许执行的任务
                      * */
                    val krisv_taskSummaries: util.List[TaskSummary] = taskService.getTasksAssignedAsPotentialOwner("krisv", "en-US")
                    for (taskSummary <- krisv_taskSummaries.asScala) {
                        println(taskSummary.getDescription)

                        /**
                          * II-1) 开始执行任务
                          * */
                        taskService.start(taskSummary.getId(), "krisv")

                        /**
                          * II-2) 结束任务的同时设置 performance 变量。在这个例子中 performance 的值将被用来控制流程的流向。
                          * */
                        val results = Map[String, AnyRef](
                            "performance" -> "exceeding"
                        )
                        taskService.complete(taskSummary.getId(), "krisv", results.asJava)
                    }

                    /**
                      * III) 再次获得 john 的任务，应该可以得到了.
                      *
                      * krisv 的后续任务会产生 john 和 mary 两条分支。因此他们可以并发得到各自的任务。
                      * */
                    val john_taskSummaries = taskService.getTasksAssignedAsPotentialOwner("john", "en-UK")
                    val mary_taskSummaries = taskService.getTasksAssignedAsPotentialOwner("mary", "en-UK")
                    for (taskSummary <- john_taskSummaries.asScala) {
                        taskService.start(taskSummary.getId(), "john")
                        taskService.complete(taskSummary.getId(), "john", Map[String, AnyRef]("performance" -> "acceptable").asJava)
                    }
                    for (taskSummary <- mary_taskSummaries.asScala) {
                        taskService.start(taskSummary.getId(), "mary")
                        taskService.complete(taskSummary.getId(), "mary", Map[String, AnyRef]("performance" -> "outstanding").asJava)
                    }

                    /** 任务结束后会出发事件。参考 createSession 函数 */
                }
            ).map(_ =>
                /** 任务结束，销毁 Session */
                closeSession.run(session)
            )
        }.map(_ =>
            /** 全部结束，销毁 engine */
            destroyEngine(m)
        ).run(session)).run(e)}.run("flows/Example_1_Evaluation.bpmn")
        .unsafeRunSync()  // 执行 init
        .unsafeRunSync()  // 执行 createSession 乃至结束
        .unsafeRunSync()
}
