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

package com.netflix.zuul.groovy;

import com.netflix.zuul.DynamicCodeCompiler;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.spy;

/**
 * Groovy code compiler
 * User: mcohen
 * Date: 5/30/13
 * Time: 11:38 AM
 * To change this template use File | Settings | File Templates.
 */
public class GroovyCompiler implements DynamicCodeCompiler {

    private static final Logger LOG = LoggerFactory.getLogger(GroovyCompiler.class);

    /**
     * Compiles Groovy code and returns the Class of the compiles code.
     *
     * 编译Groovy代码并返回编译代码的Class。
     *
     * @param sCode
     * @param sName
     * @return
     */
    public Class compile(String sCode, String sName) {
        // 一个可以加载Groovy类的ClassLoader。 加载的类被缓存，
        // 不应缓存来自其他类加载器的类。 为了能够加载之前要求但后来创建的脚本，
        // 必须不要为该类名保留类似“未找到类”的信息。 这包括可能的父装载程序。 始终重新加载未缓存的类。
        GroovyClassLoader loader = getGroovyClassLoader();
        LOG.warn("Compiling filter: " + sName);
        Class groovyClass = loader.parseClass(sCode, sName);
        return groovyClass;
    }

    /**
     * @return a new GroovyClassLoader
     */
    GroovyClassLoader getGroovyClassLoader() {
        return new GroovyClassLoader();
    }

    /**
     * Compiles groovy class from a file
     *
     * 从文件中编译groovy类
     *
     * @param file
     * @return
     * @throws java.io.IOException
     */
    public Class compile(File file) throws IOException {
        GroovyClassLoader loader = getGroovyClassLoader();
        Class groovyClass = loader.parseClass(file);
        return groovyClass;
    }

    @RunWith(MockitoJUnitRunner.class)
    public static class UnitTest {
        @Test
        public void testLoadGroovyFromString() {

            GroovyCompiler compiler = spy(new GroovyCompiler());

            try {

                String code = "class test { public String hello(){return \"hello\" } } ";
                Class clazz = compiler.compile(code, "test");
                assertNotNull(clazz);
                assertEquals(clazz.getName(), "test");
                GroovyObject groovyObject = (GroovyObject) clazz.newInstance();
                Object[] args = {};
                String s = (String) groovyObject.invokeMethod("hello", args);
                assertEquals(s, "hello");


            } catch (Exception e) {
                assertFalse(true);
            }

        }
    }
}

