/*
 * Copyright 2018 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.qos.ratelimit;

import com.palantir.atlasdb.qos.config.SimpleThrottlingStrategy;
import com.palantir.atlasdb.qos.config.ThrottlingStrategy;

public final class ThrottlingStrategies {

    private ThrottlingStrategies() {
        //utility class
    }

    public static ThrottlingStrategy getThrottlingStrategy(ThrottlingStrategyEnum throttlingStrategy) {
        if (throttlingStrategy == ThrottlingStrategyEnum.SIMPLE) {
            return new SimpleThrottlingStrategy();
        }
        throw new IllegalArgumentException("Found unknown throttling strategy in the config : " + throttlingStrategy);
    }
}
