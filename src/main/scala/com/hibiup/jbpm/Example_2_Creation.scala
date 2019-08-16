package com.hibiup.jbpm

import java.io.{File, StringReader}
import java.util.ServiceLoader

import cats.data.Kleisli
import cats.effect.IO
import javax.persistence.Persistence
import org.jbpm.bpmn2.xml.XmlBPMNProcessDumper
import org.jbpm.ruleflow.core.{RuleFlowProcess, RuleFlowProcessFactory}
import org.kie.api.KieServices
import org.kie.api.io.Resource
import org.apache.commons.io.{FileUtils => AFileUtils}
import org.drools.persistence.jta.JtaTransactionManager
import org.jbpm.kie.services.impl.admin.UserTaskAdminServiceImpl
import org.jbpm.process.instance.impl.Action
import org.jbpm.services.api.utils.KieServiceConfigurator
import org.jbpm.services.task.impl.factories.TaskFactory
import org.jbpm.services.task.impl.model.OrganizationalEntityImpl
import org.jbpm.services.task.utils.TaskFluent
import org.jbpm.services.task.wih.NonManagedLocalHTWorkItemHandler
import org.jbpm.shared.services.impl.TransactionalCommandService
import org.jbpm.workflow.core.DroolsAction
import org.jbpm.workflow.core.impl.ConnectionImpl
import org.jbpm.workflow.core.node.{ActionNode, EndNode, HumanTaskNode}
import org.kie.api.runtime.EnvironmentName
import org.kie.api.runtime.process.ProcessContext
import org.kie.api.task.model.{OrganizationalEntity, PeopleAssignments}
import org.kie.internal.task.api.TaskModelProvider
import org.kie.internal.task.api.model.InternalOrganizationalEntity

import scala.jdk.CollectionConverters._

object Example_2_Creation {
    def createProcess: Kleisli[IO, (String, RuleFlowProcessFactory => RuleFlowProcess), RuleFlowProcess] = Kleisli{ case (name, creation) => IO{
        val factory = RuleFlowProcessFactory.createProcess(name)
        creation(factory)
    }}
    
    implicit val xmlBPMNProcessDumper: XmlBPMNProcessDumper = XmlBPMNProcessDumper.INSTANCE
    
    def process2xml(implicit dumper: XmlBPMNProcessDumper):Kleisli[IO, RuleFlowProcess, String] = Kleisli{ process => IO{
        dumper.dump(process)
    }}
    
    implicit val ks: KieServices = KieServices.Factory.get
    
    def xml2resource(implicit ks:KieServices):Kleisli[IO, String, Resource] = Kleisli{ xml => IO{
        ks.getResources.newByteArrayResource(xml.getBytes())
    }}
    
    def save(groupId:String, artifactId:String, version:String, filePath:String)(implicit ks:KieServices):Kleisli[IO, Resource, Unit] = Kleisli{ resource => IO{
        resource.setSourcePath(filePath)
        val kFileSystem = ks.newKieFileSystem()
        kFileSystem.write(resource)
        
        val content = resource.getInputStream
        val output = new File(filePath)
        AFileUtils.copyInputStreamToFile(content, output)
    }}
    
    import Example_1_Evaluation.{environment, manager, engine, createSession, start, closeSession, destroyEngine}
    def init: Kleisli[IO, (String, RuleFlowProcessFactory => RuleFlowProcess), Resource] = createProcess andThen process2xml andThen xml2resource
    
    import org.jbpm.kie.services.impl.admin.UserTaskAdminServiceImpl._
    import org.jbpm.kie.services.impl.admin.commands.AddPeopleAssignmentsCommand
    import org.jbpm.kie.services.impl.admin.commands.AddTaskInputsCommand
    import org.jbpm.kie.services.impl.admin.commands.CancelTaskDeadlineCommand
    import org.jbpm.kie.services.impl.admin.commands.ListTaskNotificationsCommand
    import org.jbpm.kie.services.impl.admin.commands.ListTaskReassignmentsCommand
    import org.jbpm.kie.services.impl.admin.commands.RemovePeopleAssignmentsCommand
    import org.jbpm.kie.services.impl.admin.commands.RemoveTaskDataCommand
    import org.jbpm.kie.services.impl.admin.commands.ScheduleTaskDeadlineCommand
    import org.jbpm.services.api.RuntimeDataService
    
    val process: IO[Resource] = init.run(
        "com.hibiup.HelloBPMN",
        factory => {
            factory
              .name("AutoCreationBPMN")
              .version("1.0")
              .packageName("com.hibiup")
              /**
                * 添加启动节点。
                *
                * 注意的是，Node id 不等于 task id，因此当我们用 TaskService 的 getTaskById 取得 task 的时候，并不能依靠此 id
                * */
              .startNode(1).name("Start").done()
              /**
                * 添加脚本任务,
                *
                * 脚本任务会自动执行
                * */
              .actionNode(2)
                .action("java", """System.out.println("Hello, BPMN!");""")
                .done()
              /**
                * 添加 human task node.
                *
                * 一个 task node 可以包含 0 到多个 task，因此 node id 不等于 task id，task id 自动从 1 开始编码. 任务缺省也没有 owner
                * */
              .humanTaskNode(3)
                .taskName("First human task").name("Self Evaluation").actorId("#{UserName}")
                .done()
              /**
                * 添加终止节点
                * */
              .endNode(4).name("End").done()
              .connection(1, 2).connection(2, 3).connection(3, 4)

            /**
              * 以上在建立 process 的时候同时添加任务，也可以先生成 process, 然后逐个添加任务到 process 中去，参考：
              *
              * https://github.com/marianbuenosayres/jBPM6-Developer-Guide/blob/master/chapter-02/jBPM6-quickstart/src/test/java/com/wordpress/marianbuenosayres/quickstart/ProgrammedProcessExecutionTest.java
              * */

            val process = factory.validate().getProcess

            /**
              * 在返回 process 之前，还可以获得之前定义的 Node，添加为它们添加更多属性(然并卵。。。)：
              * */
            process.getNode(3) match {
                case node:HumanTaskNode => {
                    /**
                      * node 的 work 属性。。。
                      * */
                    val work = node.getWork
                    assert("#{UserName}" == work.getParameter("ActorId"))

                    /**
                      * 尝试为 Node 3 的 task 添加 Owner（失败。。。）。
                      * */
                    //val emf = Persistence.createEntityManagerFactory("org.jbpm.persistence.jpa")
                    //val serviceConfigurator = ServiceLoader.load(classOf[KieServiceConfigurator]).iterator().next()
                    //val runtimeDataService = serviceConfigurator.getRuntimeDataService
                    //val userTaskService = serviceConfigurator.getUserTaskService
                    
                    //val taskModelFactory = TaskModelProvider.getFactory
                    //val userTaskAdminService = new UserTaskAdminServiceImpl
                    //userTaskAdminService.setUserTaskService(userTaskService)
                    //userTaskAdminService.setRuntimeDataService(runtimeDataService)
                    //userTaskAdminService.setCommandService(new TransactionalCommandService(emf))
                    //userTaskAdminService.addPotentialOwners(0, false, taskModelFactory.newUser("john"))
                }
            }
            
            /**
              * 返回 process
              * */
            process
        }
    )
    
    def _main = process.map{resource => (environment andThen manager andThen engine).map{ case (m, e) => {
        /** Save to file*/
        save("com.hibiup","HelloBPMN","1.0", "src/main/resources/flows/Example_2_Creation.bpmn").run(resource).attempt.unsafeRunSync match {
            case Right(r) => println(s"Save successfully")
            case Left(t) => t.printStackTrace()
        }
    
        val params = Map[String, AnyRef](
            "UserName" -> "john"
        )
        
        /** Run process */
        createSession.map(session => {
            start(e, "com.hibiup.HelloBPMN", params = params.asJava).map( taskService =>{
                // TODO:

                /**
                  * 添加一个 Task. 参考：
                  *
                  * https://github.com/kiegroup/jbpm/blob/master/jbpm-human-task/jbpm-human-task-core/src/test/java/org/jbpm/services/task/TaskQueryServiceBaseTest.java
                  * https://github.com/kiegroup/jbpm/blob/master/jbpm-human-task/jbpm-human-task-audit/src/test/java/org/jbpm/services/task/audit/service/TaskAuditBaseTest.java
                  * */
                /*
                val newTaskDefinition =
                    """
                      | (with (new Task()) { priority = 55, taskData = (with( new TaskData()) { } ),
                      | peopleAssignments = (with ( new PeopleAssignments() ) { potentialOwners = [new User('john')], businessAdministrators = [ new User('Administrator') ], }),
                      | name = 'This is my task name' })
                    """.stripMargin
                val newTask = TaskFactory.evalTask(new StringReader(newTaskDefinition))
                */

                val newTask = new TaskFluent()
                    .setName("New Task")
                    .addPotentialUser("john")
                    .setAdminUser("john")
                    .setDeploymentID("default-singleton")
                    .getTask

                taskService.addTask(newTask, Map[String, AnyRef]().asJava)
                // 因为已经存在一个 task 了，所以这个 task 的 id 会自动成为 2
                assert(newTask.getId == 2)

                /**
                * 尝试取得第一个 humanTask（taskId = 1）。因任务缺省没有owner，因此当我们尝试取得的时候，只能通过 id（1）或 Administrator
                * */
                val task1 = taskService.getTaskById(1)
                assert(task1.getId == 1)
                assert(task1.getName=="Self Evaluation")
                assert(task1.getFormName=="First human task")

                // Add potential owner
                TaskModelProvider.getFactory.newUser() match {
                    case john: InternalOrganizationalEntity => {
                        john.setId("john")
                        task1.getPeopleAssignments.getPotentialOwners.add(john)
                    }
                }

                // 查看 assignments
                val assignments: PeopleAssignments = task1.getPeopleAssignments
                assert(assignments.getPotentialOwners.size() > 0)

                // 通过 Administrator 或 PotentialOwner 取得 unit.List[TaskSummary]
                val taskSummaries = taskService.getTasksAssignedAsBusinessAdministrator("Administrator", "en_US")
                for( taskSummary <- taskSummaries.asScala) {
                    taskService.start(taskSummary.getId, "Administrator")  // 必须是合法用户（owner 或 admin）并且具有权限。参考 Example_1_Evaluation
                    taskService.complete(taskSummary.getId, "Administrator", null)
                }

                /** 尝试执行这个新添加的 Task（会抛出异常） */
                val johnTasks = taskService.getTasksAssignedAsPotentialOwner("john", "en_US")
                for( taskSummary <- johnTasks.asScala) {
                    /** 新添加的 task 处于 reserved 状态，要重新设置回 ready 状态。*/
                    taskService.release(taskSummary.getId, "john")
                    taskService.start(taskSummary.getId, "john")  // 必须是合法用户（owner 或 admin）并且具有权限。参考 Example_1_Evaluation
                    taskService.complete(taskSummary.getId, "john", null)
                }

            }).map( _ =>
                closeSession.run(session)
            )
        }.map(_ =>
            destroyEngine(m)
        ).run(session)).run(e)
    }}.run(resource)}
      .unsafeRunSync()
      .unsafeRunSync()
      .unsafeRunSync()
      .unsafeRunSync()
}
