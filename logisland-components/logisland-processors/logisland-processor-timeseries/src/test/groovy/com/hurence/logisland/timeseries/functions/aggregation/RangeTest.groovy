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
package com.hurence.logisland.timeseries.functions.aggregation

import com.hurence.logisland.timeseries.MetricTimeSeries
import com.hurence.logisland.timeseries.functions.FunctionValueMap
import spock.lang.Specification

/**
 * Range analysis unit test
 * @author f.lautenschlager
 */
class RangeTest extends Specification {

    def "test range analysis"() {
        MetricTimeSeries.Builder timeSeries = new MetricTimeSeries.Builder("Range","metric")
        10.times {
            timeSeries.point(it, it)
        }
        timeSeries.point(11, -5)

        MetricTimeSeries ts = timeSeries.build()
        def analysisResult = new FunctionValueMap(1, 1, 1)
        when:
        new Range().execute(ts, analysisResult)
        then:
        analysisResult.getAggregationValue(0) == 14.0d

    }

    def "test for empty time series"() {
        given:
        def analysisResult = new FunctionValueMap(1, 1, 1)
        when:
        new Range().execute(new MetricTimeSeries.Builder("Empty","metric").build(), analysisResult)
        then:
        analysisResult.getAggregationValue(0) == Double.NaN
    }


    def "test arguments"() {
        expect:
        new Range().getArguments().length == 0
    }

    def "test type"() {
        expect:
        new Range().getQueryName() == "range"
    }

    def "test equals and hash code"() {
        expect:
        def range = new Range()
        !range.equals(null)
        !range.equals(new Object())
        range.equals(range)
        range == (new Range())
        new Range().hashCode() == new Range().hashCode()
    }

}
