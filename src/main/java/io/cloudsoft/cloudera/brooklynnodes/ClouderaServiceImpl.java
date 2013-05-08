package io.cloudsoft.cloudera.brooklynnodes;

import io.cloudsoft.cloudera.rest.ClouderaRestCaller;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.render.RendererHints;
import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.basic.Description;
import brooklyn.entity.basic.Lifecycle;
import brooklyn.entity.basic.NamedParameter;
import brooklyn.event.feed.function.FunctionFeed;
import brooklyn.event.feed.function.FunctionPollConfig;
import brooklyn.location.Location;

import com.google.common.base.Functions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class ClouderaServiceImpl extends AbstractEntity implements ClouderaService {

    private static final Logger log = LoggerFactory.getLogger(ClouderaServiceImpl.class);
        
    static {
        RendererHints.register(ClouderaService.SERVICE_URL, new RendererHints.NamedActionWithUrl("Open"));
    }

    public ClouderaRestCaller getApi() {
        return ClouderaRestCaller.newInstance(getConfig(MANAGER).getAttribute(ClouderaManagerNode.CLOUDERA_MANAGER_HOSTNAME), "admin", "admin");
    }

    public void create() {
        if (getAttribute(SERVICE_STATE)!=null) {
            throw new IllegalStateException("${this} is already created");
        }
        setAttribute(SERVICE_STATE, Lifecycle.STARTING);
        
        log.info("Creating CDH service ${this}");
        Boolean buildResult = getConfig(TEMPLATE).build(getApi());
        log.info("Created CDH service ${this}, result: "+buildResult);
        
        setAttribute(SERVICE_STATE, buildResult ? Lifecycle.RUNNING : Lifecycle.ON_FIRE);
        setAttribute(SERVICE_NAME, getConfig(TEMPLATE).getName());
        setAttribute(CLUSTER_NAME, getConfig(TEMPLATE).getClusterName());
        connectSensors();
    }
    
    protected void connectSensors() {
        /*
        if (!sensorRegistry) sensorRegistry = new SensorRegistry(this);
        FunctionSensorAdapter fnSensorAdaptor = sensorRegistry.register(new FunctionSensorAdapter({ getApi() }, period: 30*TimeUnit.SECONDS));
        fnSensorAdaptor.poll(SERVICE_REGISTERED, { it.getServices(getClusterName()).contains(getServiceName()) });
        def roles = fnSensorAdaptor.then({ it.getServiceRolesJson(getClusterName(), getServiceName()) });
        roles.poll(HOSTS) { it.items.collect { it.hostRef.hostId } }
        roles.poll(ROLES) { (it.items.collect { it.type }) as Set }
        def state = fnSensorAdaptor.then({ it.getServiceJson(getClusterName(), getServiceName()) });
        state.poll(SERVICE_UP, { it.serviceState == "STARTED" });
        state.poll(SERVICE_HEALTH, { it.healthSummary });
        state.poll(SERVICE_URL, { it.serviceUrl });
        sensorRegistry.activateAdapters();
        */
        FunctionFeed feed = FunctionFeed.builder()
                .entity(this)
                .poll(new FunctionPollConfig<Boolean,Boolean>(SERVICE_REGISTERED)
                    .period(30, TimeUnit.SECONDS)
                    .callable(new Callable<Boolean>() {
                        @Override
                        public Boolean call() throws Exception {
                            try {
                                return (getApi().getServices(getClusterName()).contains(getServiceName()));
                            }
                            catch (Exception e) {
                                return false;
                            }
                        }
                    })
                    .onError(Functions.constant(false))
                    )
                .poll(new FunctionPollConfig<Collection<String>, Collection<String>>(HOSTS)
                .period(30, TimeUnit.SECONDS)
                .callable(new Callable<Collection<String>>() {
                    @Override
                    public Collection<String> call() throws Exception {
                        for(Object element : ((Iterable<Object>) getApi().getServiceRolesJson(getClusterName(), getServiceName()))) {
                            System.out.println(element.getClass());
                            //return it.items.collect { it.hostRef.hostId };
                        }
                        return Lists.newArrayList();
                    }
                })
                //.onError(Functions.constant(false))
                )
                .poll(new FunctionPollConfig<Collection<String>, Collection<String>>(ROLES)
                    .period(30, TimeUnit.SECONDS)
                    .callable(new Callable<Collection<String>>() {
                        @Override
                        public Collection<String> call() throws Exception {
                            for(Object element : ((Iterable<Object>) getApi().getServiceRolesJson(getClusterName(), getServiceName()))) {
                                System.out.println(element.getClass());
                                //return (it.items.collect { it.type }) as Set;
                            }
                            return Sets.newHashSet();
                        }
                    })
                    //.onError(Functions.constant(false))
                    )
                .poll(new FunctionPollConfig<Boolean,Boolean>(SERVICE_UP)
                    .period(30, TimeUnit.SECONDS)
                    .callable(new Callable<Boolean>() {
                        @Override
                        public Boolean call() throws Exception {
                            try {
                                Object serviceJson = getApi().getServiceJson(getClusterName(), getServiceName());
                                return serviceJson != null;
                                //return (it.serviceState == "STARTED");
                            }
                            catch (Exception e) {
                                return false;
                            }
                        }
                    })
                    .onError(Functions.constant(false))
                    )
                .poll(new FunctionPollConfig<String, String>(SERVICE_HEALTH)
                    .period(30, TimeUnit.SECONDS)
                    .callable(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Object serviceJson = getApi().getServiceJson(getClusterName(), getServiceName());
                            return (String) serviceJson;
                            //return it.healthSummary;
                        }
                    })
                    //.onError(Functions.constant(false))
                    )
                .poll(new FunctionPollConfig<String, String>(SERVICE_URL)
                    .period(30, TimeUnit.SECONDS)
                    .callable(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Object serviceJson = getApi().getServiceJson(getClusterName(), getServiceName());
                            return (String) serviceJson;
                            //return it.serviceUrl;
                        }
                    })
                   // .onError(Functions.constant(false))
                    )
                .build();
    }
    
    String getClusterName() { return getAttribute(CLUSTER_NAME); }
    String getServiceName() { return getAttribute(SERVICE_NAME); }
    
    @Override
    @Description("Start the process/service represented by an entity")
    public void start(@NamedParameter("locations") Collection<? extends Location> locations) {
        if (locations == null) log.debug("Ignoring locations at ${this}");
        if (getAttribute(SERVICE_STATE)==Lifecycle.RUNNING) {
            log.debug("Ignoring start when already started at ${this}");
            return;
        }
        setAttribute(SERVICE_STATE, Lifecycle.STARTING);
        invokeServiceCommand("start");
        setAttribute(SERVICE_STATE, Lifecycle.RUNNING);
    }

    
    @Override
    @Description("Stop the process/service represented by an entity")
    public void stop() {
        if (getAttribute(SERVICE_STATE)==Lifecycle.STOPPED) {
            log.debug("Ignoring stop when already stopped at ${this}");
            return;
        }
        setAttribute(SERVICE_STATE, Lifecycle.STOPPING);
        invokeServiceCommand("stop");
        setAttribute(SERVICE_STATE, Lifecycle.STOPPED);
    }

    @Override
    @Description("Restart the process/service represented by an entity")
    public void restart() {
        setAttribute(SERVICE_STATE, Lifecycle.STARTING);
        invokeServiceCommand("restart");
        setAttribute(SERVICE_STATE, Lifecycle.RUNNING);
    }

}