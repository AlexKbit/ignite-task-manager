package com.alexkbit.intro.ignite.service.cluster;

import com.alexkbit.intro.ignite.model.Job;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteQueue;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cluster.ClusterMetrics;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CollectionConfiguration;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.apache.ignite.services.Service;
import org.apache.ignite.services.ServiceContext;

/**
 * Cluster service for execute tasks.
 */
@Slf4j
@NoArgsConstructor
public class ClusterExecuteService implements Service {

    public static final String JOB_QUEUE = "JOB_QUEUE";
    public static final String SERVICE_NAME = "clusterExecuteService";

    @IgniteInstanceResource
    private transient Ignite ignite;
    private transient IgniteQueue<Job> jobQueue;
    private transient TaskCacheStore cacheStore;

    public ClusterExecuteService(Ignite ignite) {
        this.ignite = ignite;
    }

    @Override
    public void cancel(ServiceContext ctx) {
    }

    @Override
    public void init(ServiceContext ctx) throws Exception {
        CollectionConfiguration colCfg = new CollectionConfiguration();
        colCfg.setCacheMode(CacheMode.REPLICATED);
        jobQueue = ignite.queue(ClusterExecuteService.JOB_QUEUE, 0, colCfg);
        cacheStore = new TaskCacheStore(ignite);
    }

    @Override
    public void execute(ServiceContext ctx) throws Exception {
        while (!ctx.isCancelled()) {
            execute();
        }
    }

    public IgniteFuture<String> execute() {
        if (freeThreadExists() && !jobQueue.isEmpty()) {
            return executeJob(jobQueue.poll());
        }
        return null;
    }

    private IgniteFuture<String> executeJob(Job job) {
        if (job == null) {
            return null;
        }
        try {
            log.debug(String.format("Start execution job[%s] for task[%s]", job.getJobId(), job.getTaskId()));
            IgniteFuture<String> future = ignite.compute().callAsync(job);
            future.listen(t -> log.debug(String.format("End execution job[%s] for task[%s]", job.getJobId(), job.getTaskId())));
            return future;
        } catch (Exception e) {
            cacheStore.saveError(job.getTaskId(), e.getMessage());
        }
        return null;
    }

    private boolean freeThreadExists() {
        int computePoolSize = ignite.configuration().getPublicThreadPoolSize();
        int currentActiveTasksInCluster = 0;
        for (ClusterNode clusterNode : ignite.cluster().topology(ignite.cluster().topologyVersion())) {
            ClusterMetrics clusterMetrics = clusterNode.metrics();
            currentActiveTasksInCluster += clusterMetrics.getCurrentActiveJobs();
        }
        return computePoolSize - currentActiveTasksInCluster > 0;
    }
}
