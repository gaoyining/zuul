/*
 * Copyright 2018 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package com.netflix.zuul;

import com.netflix.servo.monitor.DynamicCounter;
import com.netflix.zuul.filters.ZuulFilter;

/**
 * Publishes a counter metric for each filter on each use.
 *
 * 基本过滤器使用通知程序
 * 在每次使用时为每个过滤器发布计数器度量标准。
 */
public class BasicFilterUsageNotifier implements FilterUsageNotifier {
    private static final String METRIC_PREFIX = "zuul.filter-";

    @Override
    public void notify(ZuulFilter filter, ExecutionStatus status) {
        DynamicCounter.increment(METRIC_PREFIX + filter.getClass().getSimpleName(), "status", status.name(), "filtertype", filter.filterType().toString());
    }
}

