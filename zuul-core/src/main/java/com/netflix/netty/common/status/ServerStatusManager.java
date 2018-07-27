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

package com.netflix.netty.common.status;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.DiscoveryClient;

import javax.inject.Inject;
import javax.inject.Singleton;

import static com.netflix.appinfo.InstanceInfo.InstanceStatus.UNKNOWN;
import static com.netflix.appinfo.InstanceInfo.InstanceStatus.UP;

/**
 *
 * eureka服务器状态管理
 *
 * User: michaels@netflix.com
 * Date: 7/6/17
 * Time: 3:37 PM
 */
@Singleton
public class ServerStatusManager
{
    /**
     * 应用信息管理
     */
    private final ApplicationInfoManager applicationInfoManager;
    /**
     * eureka客户端
     */
    private final DiscoveryClient discoveryClient;

    @Inject
    public ServerStatusManager(ApplicationInfoManager applicationInfoManager, DiscoveryClient discoveryClient)
    {
        this.applicationInfoManager = applicationInfoManager;
        this.discoveryClient = discoveryClient;
    }

    public InstanceInfo.InstanceStatus status() {

        // NOTE: when debugging this locally, found to my surprise that when the instance is maked OUT_OF_SERVICE remotely
        // in Discovery, although the StatusChangeEvent does get fired, the _local_ InstanceStatus (ie.
        // applicationInfoManager.getInfo().getStatus()) does not get changed to reflect that.
        // So that's why I'm doing this little dance here of looking at both remote and local statuses.

        // 注意：在本地调试时，令我惊讶的是，当实例远程调用OUT_OF_SERVICE时
        // 在Discovery中，虽然StatusChangeEvent被触发，但_local_ InstanceStatus（即。
        // applicationInfoManager.getInfo（）。getStatus（））没有被改变以反映这一点。
        // 这就是为什么我在这里做这个小舞蹈，看看远程和本地状态。


        // 本地状态
        InstanceInfo.InstanceStatus local = localStatus();
        // 远程状态
        InstanceInfo.InstanceStatus remote = remoteStatus();

        if (local == UP && remote != UNKNOWN) {
            return remote;
        }
        else {
            return local;
        }
    }

    public InstanceInfo.InstanceStatus localStatus() {
        return applicationInfoManager.getInfo().getStatus();
    }

    public InstanceInfo.InstanceStatus remoteStatus() {
        return discoveryClient.getInstanceRemoteStatus();
    }

    public void localStatus(InstanceInfo.InstanceStatus status) {
        applicationInfoManager.setInstanceStatus(status);
    }

    public int health() {
        // TODO
        throw new UnsupportedOperationException();
    }
}
