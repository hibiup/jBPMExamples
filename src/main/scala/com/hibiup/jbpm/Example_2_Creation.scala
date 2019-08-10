package com.hibiup.jbpm

import java.io.OutputStreamWriter

import cats.data.Kleisli
import cats.effect.IO
import org.jbpm.bpmn2.xml.XmlBPMNProcessDumper
import org.jbpm.ruleflow.core.{RuleFlowProcess, RuleFlowProcessFactory}
import org.kie.api.KieServices
import org.kie.api.io.{KieResources, Resource}

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
        //val releaseId = ks.newReleaseId(groupId, artifactId, version)
        //kFileSystem.generateAndWritePomXML(releaseId)
        //ks.newKieBuilder(kFileSystem).buildAll()
        //ks.newKieContainer(releaseId).newKieSession().startProcess(s"""$groupId.$artifactId""")
    }}
    
    import Example_1_Evaluation.{environment, manager, engine, createSession, closeSession, destroyEngine}
    def init: Kleisli[IO, (String, RuleFlowProcessFactory => RuleFlowProcess), Resource] = createProcess andThen process2xml andThen xml2resource
    
    val process = init.run(
        "com.hibiup.HelloBPMN",
        factory => {
            factory
              .name("AutoCreationBPMN")
              .version("1.0")
              .packageName("com.hibiup")
              .startNode(1).name("Start").done()
              .actionNode(2).action("java", """System.out.println("Hello, BPMN!");""").done()
              .endNode(3).name("End").done()
              .connection(1, 2).connection(2, 3)
            
            factory.validate().getProcess
        }
    )
    
    def _main = process.map{resource => (environment andThen manager andThen engine).map{ case (m, e) => {
        /** Save to file*/
        save("com.hibiup","HelloBPMN","1.0", "src/main/resources/flows/Example_2_Creation.bpmn").run(resource).attempt.unsafeRunSync match {
            case Right(r) => println(s"Save successfully")
            case Left(t) => t.printStackTrace()
        }
        
        /** Run process */
        createSession.map(session => {
          // TODO:
          session.startProcess("com.hibiup.HelloBPMN")
          
          closeSession.run(session)
        }.map(_ =>
            destroyEngine(m)
        )).run(e)
    }}.run(resource)}
      .unsafeRunSync()
      .unsafeRunSync()
      .unsafeRunSync()
      .unsafeRunSync()
}