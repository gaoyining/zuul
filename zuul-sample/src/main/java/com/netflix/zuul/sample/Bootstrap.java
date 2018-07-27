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

package com.netflix.zuul.sample;

import com.google.inject.Injector;
import com.netflix.config.ConfigurationManager;
import com.netflix.governator.InjectorBuilder;
import com.netflix.zuul.netty.server.BaseServerStartup;
import com.netflix.zuul.netty.server.Server;

/**
 * Bootstrap
 *
 * Author: Arthur Gonigberg
 * Date: November 20, 2017
 */
public class Bootstrap {

    public static void main(String[] args) {
        new Bootstrap().start();
    }

    public void start() {
        System.out.println("Zuul Sample: starting up.");
        long startTime = System.currentTimeMillis();
        int exitCode = 0;

        Server server = null;

        try {
            // 首先加载资源configName.properties。
            // 然后将configName-deploymentEnvironment.properties加载到系统范围的配置中。
            // 例如，如果configName是“application”，并且部署环境是“test”，
            // 则此API将首先加载“application.properties”，然后加载“application-test.properties”
            // 以覆盖“application.properties”中也存在的任何属性。”。
            ConfigurationManager.loadCascadedPropertiesFromResources("application");
            Injector injector = InjectorBuilder.fromModule(new ZuulSampleModule()).createInjector();
            BaseServerStartup serverStartup = injector.getInstance(BaseServerStartup.class);
            server = serverStartup.server();

            long startupDuration = System.currentTimeMillis() - startTime;
            System.out.println("Zuul Sample: finished startup. Duration = " + startupDuration + " ms");

            // -----------------------关键方法----------------------
            // 启动server
            server.start(true);
        }
        catch (Throwable t) {
            t.printStackTrace();
            System.err.println("###############");
            System.err.println("Zuul Sample: initialization failed. Forcing shutdown now.");
            System.err.println("###############");
            exitCode = 1;
        }
        finally {
            // server shutdown
            if (server != null) server.stop();

            System.exit(exitCode);
        }
    }
}
