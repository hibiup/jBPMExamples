lazy val dependenciesManager = new {
    val scalaTestVersion = "3.0.8"
    val logBackVersion = "1.2.3"
    val scalaLoggingVersion = "3.9.2"
    val catsVersion = "2.0.0-M4"
    val jBPMVersion = "7.24.0.Final"
    val h2Version = "1.4.199"
    val hibernateVersion = "5.4.4.Final"
    val narayanaJtaVersion = "5.9.5.Final"
    val common_ioVersion = "2.6"
    val freemarkerVersion = "2.3.28"

    val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion % Test
    val h2 = "com.h2database" % "h2" % h2Version % Test
    val kie_test_util = "org.kie" % "kie-test-util" % jBPMVersion % Test
    val logback = "ch.qos.logback" % "logback-classic" % logBackVersion
    val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion
    val cats = "org.typelevel" %% "cats-core" % catsVersion
    val cats_effect = "org.typelevel" %% "cats-effect" % catsVersion
    val drools = "org.drools" % "drools-core" % jBPMVersion
    val drools_compiler = "org.drools" % "drools-compiler" % jBPMVersion
    val jBPM = "org.jbpm" % "jbpm-bpmn2" % jBPMVersion
    val jBPM_test = "org.jbpm" % "jbpm-test" % jBPMVersion % Test
    val jBPM_test_util = "org.jbpm" % "jbpm-test-util" % jBPMVersion % Test
    val jBPM_audit = "org.jbpm" % "jbpm-audit" % jBPMVersion
    val jBPM_flow = "org.jbpm" % "jbpm-flow" % jBPMVersion
    val jBPM_runtime_manager = "org.jbpm" % "jbpm-runtime-manager" % jBPMVersion
    val jBPM_human_task_core = "org.jbpm" % "jbpm-human-task-core" % jBPMVersion
    val jBPM_kie_services = "org.jbpm" % "jbpm-kie-services" % jBPMVersion
    val jBPM_services_api = "org.jbpm" % "jbpm-services-api" % jBPMVersion
    val jBPM_shared_services = "org.jbpm" % "jbpm-shared-services" % jBPMVersion
    val hibernate_entitymanager = "org.hibernate" % "hibernate-entitymanager" % hibernateVersion
    val narayanaJta = "org.jboss.narayana.jta" % "narayana-jta" % narayanaJtaVersion
    val common_io = "commons-io" % "commons-io" % common_ioVersion
    val freemarker = "org.freemarker" % "freemarker" % freemarkerVersion
}

lazy val dependencies = Seq(
    dependenciesManager.scalaTest,
    dependenciesManager.h2,
    dependenciesManager.logback,
    dependenciesManager.scalaLogging,
    dependenciesManager.cats,
    dependenciesManager.cats_effect,
    dependenciesManager.drools,
    dependenciesManager.drools_compiler,
    dependenciesManager.jBPM,
    dependenciesManager.jBPM_audit,
    dependenciesManager.jBPM_flow,
    dependenciesManager.jBPM_human_task_core,
    dependenciesManager.jBPM_kie_services exclude("org.freemarker", "freemarker"),
    dependenciesManager.jBPM_services_api,
    dependenciesManager.jBPM_shared_services,
    dependenciesManager.jBPM_runtime_manager,
    dependenciesManager.hibernate_entitymanager,
    dependenciesManager.narayanaJta,
    dependenciesManager.freemarker,
    /** 
      * kie_test_util 提供了数据库初始化函数 PersistenceUtil.setupPoolingDataSource() 
      * */
    dependenciesManager.kie_test_util,
    /** 
      * jBPM_test 提供了供测试用的 persistence 配置文件 META-INF/persistence.xml 和必要的环境变量文件 jndi.properties，
      * 否则会出现以下错误：
      * 
      *   javax.naming.NoInitialContextException: Need to specify class name in environment or system property,
      *     or as an applet parameter, or in an application resource file:  java.naming.factory.initial
      * 和：
      *   No Persistence provider for EntityManager named org.jbpm.persistence.jpa
      *   
      * datasource.properties 则提供了数据库的连接信息。
      * */
    dependenciesManager.jBPM_test,
    // dependenciesManager.jBPM_test_util
    dependenciesManager.common_io
)

lazy val jBPMExamples = project.in(file("."))
    .settings(
        name := "jBPMExamples",
        version := "0.1",
        scalaVersion := "2.13.0",
        libraryDependencies ++= dependencies,
        scalacOptions ++= Seq(
            "-language:higherKinds",
            "-deprecation",
            "-encoding", "UTF-8",
            "-feature",
            "-language:_"
        )
    )