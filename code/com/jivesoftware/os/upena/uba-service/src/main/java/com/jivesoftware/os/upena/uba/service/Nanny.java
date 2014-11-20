/*
 * Copyright 2013 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.os.upena.uba.service;

import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;
import com.jivesoftware.os.jive.utils.shell.utils.Curl;
import com.jivesoftware.os.uba.shared.NannyReport;
import com.jivesoftware.os.upena.routing.shared.InstanceDescriptor;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Nanny {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();
    private final InstancePath instancePath;
    private final DeployableValidator deployableValidator;
    private final DeployLog deployLog;
    private final DeployableScriptInvoker invokeScript;
    private final AtomicBoolean redeploy;
    private final AtomicBoolean destroyed;
    private final AtomicReference<InstanceDescriptor> instanceDescriptor;
    private final LinkedBlockingQueue<Runnable> linkedBlockingQueue;
    private final ThreadPoolExecutor threadPoolExecutor;

    public Nanny(InstanceDescriptor instanceDescriptor,
            InstancePath instancePath,
            DeployableValidator deployableValidator,
            DeployLog deployLog,
            DeployableScriptInvoker invokeScript) {
        this.instanceDescriptor = new AtomicReference<>(instanceDescriptor);
        this.instancePath = instancePath;
        this.deployableValidator = deployableValidator;
        this.deployLog = deployLog;
        this.invokeScript = invokeScript;
        linkedBlockingQueue = new LinkedBlockingQueue<>(10);
        threadPoolExecutor = new ThreadPoolExecutor(1, 1, 1000, TimeUnit.MILLISECONDS, linkedBlockingQueue);
        redeploy = new AtomicBoolean(!instancePath.script("status").exists());
        destroyed = new AtomicBoolean(false);
    }

    public InstanceDescriptor getInstanceDescriptor() {
        return instanceDescriptor.get();
    }

    synchronized public void setInstanceDescriptor(InstanceDescriptor id) {
        InstanceDescriptor got = instanceDescriptor.get();
        if (got != null && !got.equals(id)) {
            redeploy.set(true);
            LOG.info("Instance changed from " + got + " to " + id);
        }
        if (!instancePath.script("status").exists()) {
            redeploy.set(true);
        }
        if (!redeploy.get()) {
            LOG.info("Service:" + instancePath.toHumanReadableName() + " has NOT changed.");
        }
        instanceDescriptor.set(id);
    }

    public DeployLog getDeployLog() {
        return deployLog;
    }

    public NannyReport report() {
        return new NannyReport(deployLog.getState(), instanceDescriptor.get(), deployLog.copyLog());
    }

    synchronized public String nanny(String host, String upenaHost, int upenaPort) {
        if (destroyed.get()) {
            deployLog.log("Nanny tried to check a service that has been destroyed. " + this, null);
            return deployLog.getState();
        }
        if (linkedBlockingQueue.size() == 0) {
            try {
                deployLog.clear();
                if (redeploy.get()) {
                    NannyDestroyCallable destroyTask = new NannyDestroyCallable(
                            instancePath,
                            deployLog,
                            invokeScript);
                    Future<Boolean> detroyedFuture = threadPoolExecutor.submit(destroyTask);
                    if (detroyedFuture.get()) {

                        NannyDeployCallable deployTask = new NannyDeployCallable(host, upenaHost, upenaPort,
                                instanceDescriptor.get(), instancePath,
                                deployLog, deployableValidator,
                                invokeScript);
                        Future<Boolean> deployedFuture = threadPoolExecutor.submit(deployTask);
                        if (deployedFuture.get()) {
                            redeploy.set(false);
                        }
                    }
                }

                NannyStatusCallable nannyTask = new NannyStatusCallable(
                        instanceDescriptor.get(), instancePath,
                        deployLog,
                        invokeScript);
                threadPoolExecutor.submit(nannyTask);

            } catch (InterruptedException | ExecutionException x) {
                deployLog.log("Nanny is already running. " + this, x);
            }
            return deployLog.getState();
        } else {
            return deployLog.getState();
        }
    }

    synchronized Boolean destroy() throws InterruptedException, ExecutionException {
        destroyed.set(true);
        NannyDestroyCallable nannyTask = new NannyDestroyCallable(
                instancePath,
                deployLog,
                invokeScript);
        Future<Boolean> waitForDestory = threadPoolExecutor.submit(nannyTask);
        return waitForDestory.get();

    }

    String invalidateRouting(String tenantId) {
        try {
            LOG.info("invalidating tenant routing for tenatId:" + tenantId + " on " + this);
            StringBuilder curl = new StringBuilder();
            curl.append("localhost:");
            curl.append(instanceDescriptor.get().ports.get("manage"));
            curl.append("/tenant/routing/invalidate?");
            curl.append("tenantId=").append(tenantId).append('&');
            curl.append("connectToServiceId=*").append('&');
            curl.append("portName=*");

            String response = Curl.create().curl(curl.toString());
            LOG.info(response);
            return response;
        } catch (IOException x) {
            LOG.warn("failed to invalidate tenant routing for tenantId:" + tenantId + " on " + this);
            return "failed to invalidate tenant routing for tenantId:" + tenantId + " on " + this;
        }
    }

    public void stop() {
        threadPoolExecutor.shutdownNow();
    }

    @Override
    public String toString() {
        return "Nanny{"
                + "instancePath=" + instancePath
                + ", instanceDescriptor=" + instanceDescriptor
                + '}';
    }
}
