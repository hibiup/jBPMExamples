package com.hibiup.jbpm

import org.kie.test.util.db.PersistenceUtil
import org.scalatest.FlatSpec

class Example_1_Evaluation_Test extends FlatSpec{
    "Evaluation flow test" should "" in {
        import Example_1_Evaluation._

        PersistenceUtil.setupPoolingDataSource()
        _main()
    }
}
