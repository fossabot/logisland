/*
 * Copyright (C) 2016 QAware GmbH
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.hurence.logisland.timeseries

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit test for the schema class
 *
 * @author f.lautenschlager
 */
class SchemaTest extends Specification {


    def "test private constructor"() {
        when:
        Schema.newInstance()
        then:
        noExceptionThrown()
    }

    @Unroll
    def "test field '#field' is user-defined is #expected"() {
        when:
        def result = Schema.isUserDefined(field)

        then:
        result == expected

        where:
        field << ["data", "id", "start", "end", "type", "name", "user-defined"]
        expected << [false, false, false, false, false, false, true]

    }

}
