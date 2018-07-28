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

/**
 * 执行状态
 */
public enum ExecutionStatus {

    /**
     * 成功
     */
    SUCCESS (1),
    /**
     * 跳过
     */
    SKIPPED(-1),
    /**
     * 禁用
     */
    DISABLED(-2),
    /**
     * 失败
     */
    FAILED(-3),
    /**
     * body等待
     */
    BODY_AWAIT(-4),
    /**
     * 异步等待
     */
    ASYNC_AWAIT(-5);
    
    private int status;

    ExecutionStatus(int status) {
        this.status = status;
    }
}