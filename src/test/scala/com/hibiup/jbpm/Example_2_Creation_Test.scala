package com.hibiup.jbpm

import org.kie.test.util.db.PersistenceUtil
import org.scalatest.FlatSpec

class Example_2_Creation_Test extends FlatSpec{
    "RuleFlowProcessFactory" should "be able to create BPMN from API" in {
        import Example_2_Creation._
        
        PersistenceUtil.setupPoolingDataSource()
        _main
    }
}
