package com.hibiup.jbpm

import cats.data.{Kleisli, StateT}
import cats.effect.IO
import org.kie.api.KieServices
import org.kie.api.io.ResourceType
import org.kie.api.runtime.KieSession
import org.kie.api.runtime.manager.{RuntimeEngine, RuntimeEnvironment, RuntimeEnvironmentBuilder, RuntimeManager, RuntimeManagerFactory}
import org.kie.api.task.TaskService
import org.kie.internal.runtime.manager.context.EmptyContext

/**
  * 演示如何以最简单地方式载入并执行一个流程。流程所使用案例来自 jBPM 官方案例 Evaluation
  *
  * https://docs.jboss.org/jbpm/release/7.7.0.Final/jbpm-docs/html_single/#jBPMCoreEngine
  *
  * */
object Example_1_Evaluation {
    /**
      * jBPM 的最基本组件名为 KieBase，该组件全称"knowledge base". 顾名思义它具有管理和执行每一个流程的所有“知识”。它负责解释
      * 每一个流程，客户端通过建立 KieSession 来和它交互。
      *
      * 为了简化 jBPM 的使用，包括如何定义 KieBase 和管理它与每一个客户端的会话（KieSession），jBPM 提供了一个名为 RuntimeManager
      * 的工具。对 jBPM API 的使用一般就从新建 RuntimeManager 开始。
      *
      * 1) 定义环境参数，其中包括要载入的流程定义文件的存储位置。
      * */
    val environment: Kleisli[IO, String, RuntimeEnvironment] = Kleisli{resourcePath => IO{
        RuntimeEnvironmentBuilder.Factory.get()
        .newDefaultBuilder()
        .addAsset(KieServices.Factory.get.getResources.newClassPathResource(resourcePath), ResourceType.BPMN2)
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
    val manager: Kleisli[IO, RuntimeEnvironment, RuntimeManager] = Kleisli{environment => IO(
        RuntimeManagerFactory.Factory.get().newSingletonRuntimeManager(environment)
    )}

    /**
      * 3) RuntimeManager 将 KieSession 和与之相关的流程（TaskService） 装入一个名为 RuntimeEngine 的组件以供用户使用。
      * */
    val engine: Kleisli[IO, RuntimeManager, (RuntimeManager, RuntimeEngine)] = Kleisli{manager => IO(
        (manager, manager.getRuntimeEngine(EmptyContext.get()))
    )}

    def init: Kleisli[IO, String, (RuntimeManager, RuntimeEngine)] = environment andThen manager andThen engine

    /**
      * 4) 从 RuntimeEngine 中获得已经初始化了的 Session
      * */
    val createSession: Kleisli[IO, RuntimeEngine, KieSession] = Kleisli{engine => IO(
        engine.getKieSession
    )}

    /**
      * 5) 使用 Session 来启动某个流程。KieBase 中可能管理了许多流程，每一个流程都应定义一个唯一的名称。
      * */
    def start(engine:RuntimeEngine, processId:String):StateT[IO, KieSession, TaskService] = StateT{ session => IO{
        session.startProcess(processId)
        (session, engine.getTaskService)
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
        createSession.map(session => {
            start(e, "com.sample.evaluation").map(
                taskService => {
                    // TODO:...
                    println(taskService)
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
