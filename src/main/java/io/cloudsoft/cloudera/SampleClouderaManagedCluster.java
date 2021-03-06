package io.cloudsoft.cloudera;

import static com.google.common.collect.Iterables.getOnlyElement;
import io.cloudsoft.cloudera.brooklynnodes.AllServices;
import io.cloudsoft.cloudera.brooklynnodes.ClouderaCdhNode;
import io.cloudsoft.cloudera.brooklynnodes.ClouderaCdhNodeImpl;
import io.cloudsoft.cloudera.brooklynnodes.ClouderaManagerNode;
import io.cloudsoft.cloudera.brooklynnodes.ClouderaService;
import io.cloudsoft.cloudera.brooklynnodes.DirectClouderaManager;
import io.cloudsoft.cloudera.brooklynnodes.StartupGroup;
import io.cloudsoft.cloudera.builders.HBaseTemplate;
import io.cloudsoft.cloudera.builders.HdfsTemplate;
import io.cloudsoft.cloudera.builders.MapReduceTemplate;
import io.cloudsoft.cloudera.builders.ZookeeperTemplate;
import io.cloudsoft.cloudera.rest.RestDataObjects.HdfsRoleType;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.catalog.Catalog;
import brooklyn.enricher.basic.SensorPropagatingEnricher;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.proxying.BasicEntitySpec;
import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.util.CommandLineUtil;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;

@Catalog(name = "Cloudera CDH4", description = "Launches Cloudera Distribution for Hadoop Manager with a Cloudera Manager and an initial cluster of 4 CDH nodes (resizable) and default services including HDFS, MapReduce, and HBase", iconUrl = "classpath://io/cloudsoft/cloudera/cloudera.jpg")
public class SampleClouderaManagedCluster extends AbstractApplication implements SampleClouderaManagedClusterInterface {

    static final Logger log = LoggerFactory.getLogger(SampleClouderaManagedCluster.class);
    static final String DEFAULT_LOCATION = "aws-ec2:us-east-1";

    // Admin - Cloudera Manager Node
    protected Entity admin;
    protected ClouderaManagerNode clouderaManagerNode;
    protected DynamicCluster workerCluster;
    protected AllServices services;    
    
    boolean launchDefaultServices = true;

    public StartupGroup getAdmin() {
        return (StartupGroup) admin;
    }

    public ClouderaManagerNode getManager() {
        return clouderaManagerNode;
    }

    public AllServices getServices() {
        return services;
    }

    @Override
    public void init() {
        admin = addChild(EntitySpecs.spec(StartupGroup.class).displayName("Cloudera Hosts and Admin"));

        clouderaManagerNode = (ClouderaManagerNode) admin.addChild(getEntityManager().createEntity(
                EntitySpecs.spec(DirectClouderaManager.class)));

        workerCluster = (DynamicCluster) admin.addChild(getEntityManager().createEntity(
                EntitySpecs
                        .spec(DynamicCluster.class)
                        .displayName("CDH Nodes")
                        .configure(
                                "factory",
                                ClouderaCdhNodeImpl.newFactory()
                                        .setConfig(ClouderaCdhNode.MANAGER, clouderaManagerNode))
                        .configure("initialSize", 4)));

        services = (AllServices) addChild(getEntityManager().createEntity(
                BasicEntitySpec.newInstance(AllServices.class).displayName("Cloudera Services")));
        addEnricher(SensorPropagatingEnricher.newInstanceListeningTo(clouderaManagerNode,
                ClouderaManagerNode.CLOUDERA_MANAGER_URL));
    }

    @Override
    public void launchDefaultServices(boolean enabled) {
        launchDefaultServices = enabled;
    }

    @Override
    public void startServices(boolean isCertificationCluster, boolean includeHbase) {
        // create these in sequence
        // following builds with sensible defaults, showing a few different syntaxes
        
        new HdfsTemplate().
                manager(clouderaManagerNode).discoverHostsFromManager().
                assignRole(HdfsRoleType.NAMENODE).toAnyHost().
                assignRole(HdfsRoleType.SECONDARYNAMENODE).toAnyHost().
                assignRole(HdfsRoleType.DATANODE).toAllHosts().
                formatNameNodes().
                enableMetrics(isCertificationCluster).
                buildWithEntity(clouderaManagerNode);
            
        new MapReduceTemplate().
                named("mapreduce-sample").
                manager(clouderaManagerNode).discoverHostsFromManager().
                assignRoleJobTracker().toAnyHost().
                assignRoleTaskTracker().toAllHosts().
                enableMetrics(isCertificationCluster).
                buildWithEntity(services);

        ClouderaService zk = new ZookeeperTemplate().
                manager(clouderaManagerNode).discoverHostsFromManager().
                assignRoleServer().toAnyHost().
                buildWithEntity(services);

        ClouderaService hb = null;
        if (includeHbase) {
            hb = new HBaseTemplate().
                manager(clouderaManagerNode).discoverHostsFromManager().
                assignRoleMaster().toAnyHost().
                assignRoleRegionServer().toAllHosts().
                buildWithEntity(services);
        }
                
        // seems to want a restart of ZK then HB after configuring HB
        log.info("Restarting Zookeeper after configuration change");
        zk.restart();
        if (hb != null) {
            log.info("Restarting HBase after Zookeeper restart");
            hb.restart();
        }
        log.info("CDH services now online -- "+clouderaManagerNode.getAttribute(ClouderaManagerNode.CLOUDERA_MANAGER_URL));
    }

    /**
     * Launches the application, along with the brooklyn web-console.
     */
    public static void main(String[] argv) throws Exception {
        List<String> args = Lists.newArrayList(argv);
        String port = CommandLineUtil.getCommandLineOption(args, "--port", "8081+");
        String location = CommandLineUtil.getCommandLineOption(args, "--location", DEFAULT_LOCATION);

        Stopwatch stopwatch = new Stopwatch();
        stopwatch.start();
        log.info("Start time for CDH deployment on '" + location +"'");
        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                                                    .application(
                                                            EntitySpecs.appSpec(SampleClouderaManagedClusterInterface.class)
                                                            .displayName("Brooklyn Cloudera Managed Cluster"))
                                                    .webconsolePort(port)
                                                    .location(location)
                                                    .start();
        Entities.dumpInfo(launcher.getApplications());
        SampleClouderaManagedClusterInterface app = 
                (SampleClouderaManagedClusterInterface) getOnlyElement(launcher.getApplications());
        app.startServices(true, false);
        stopwatch.stop(); 
        log.info("Time to deploy " + location + ": " + stopwatch.elapsedTime(TimeUnit.SECONDS) + " seconds");
    }

}
