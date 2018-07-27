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

import java.io.File;


/**
 * Interface to generate Classes from source code
 *
 * 从源代码生成类的接口
 *
 * User: mcohen
 * Date: 5/30/13
 * Time: 11:35 AM
 */
public interface DynamicCodeCompiler {

    /**
     * 编译Groovy代码并返回编译代码的Class。
     *
     * @param sCode
     * @param sName
     * @return
     * @throws Exception
     */
    Class compile(String sCode, String sName) throws Exception;

    /**
     * 从文件中编译groovy类
     *
     * @param file
     * @return
     * @throws Exception
     */
    Class compile(File file) throws Exception;
}
