/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.xd.dirt.server;

import java.util.Iterator;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.convert.converter.Converter;
import org.springframework.util.Assert;
import org.springframework.xd.dirt.cluster.Container;
import org.springframework.xd.dirt.cluster.ContainerRepository;
import org.springframework.xd.dirt.module.ModuleDefinitionRepository;
import org.springframework.xd.dirt.stream.StreamDefinitionRepository;
import org.springframework.xd.dirt.util.MapBytesUtility;
import org.springframework.xd.dirt.zookeeper.ChildPathIterator;
import org.springframework.xd.dirt.zookeeper.Paths;
import org.springframework.xd.dirt.zookeeper.ZooKeeperConnection;
import org.springframework.xd.dirt.zookeeper.ZooKeeperConnectionListener;
import org.springframework.xd.module.options.ModuleOptionsMetadataResolver;

/**
 * Server that watches ZooKeeper for Container arrivals and departures from the XD cluster. Each AdminServer instance
 * will attempt to request leadership, but at any given time only one AdminServer instance in the cluster will have
 * leadership status. Those instances not elected will watch the {@link xdzk.curator.Paths#ADMIN} znode so that one of
 * them will take over leadership if the leader admin closes or crashes.
 * 
 * @author Patrick Peralta
 * @author Mark Fisher
 */
public class AdminServer implements ContainerRepository, ApplicationListener<ContextRefreshedEvent> {

	/**
	 * Logger.
	 */
	private static final Logger LOG = LoggerFactory.getLogger(AdminServer.class);

	private final ZooKeeperConnection zkConnection;

	private final StreamDefinitionRepository streamDefinitionRepository;

	private final ModuleDefinitionRepository moduleDefinitionRepository;

	private final ModuleOptionsMetadataResolver moduleOptionsMetadataResolver;

	private ApplicationContext applicationContext;

	/**
	 * Cache of children under the containers path. This path is used to track containers in the cluster. Marked
	 * volatile because this reference is updated by the Curator event dispatch thread and read by public method
	 * {@link #getContainerPaths}.
	 */
	private volatile PathChildrenCache containers;

	/**
	 * Cache of children under the streams path. This path is used to track stream deployment requests. Marked volatile
	 * because this reference is written by the Curator thread that handles leader election.
	 */
	private volatile PathChildrenCache streams;

	/**
	 * Cache of children under the jobs path. This path is used to track job deployment requests. Marked volatile
	 * because this reference is written by the Curator thread that handles leader election.
	 */
	private volatile PathChildrenCache jobs;

	/**
	 * Converter from {@link ChildData} types to {@link Container}.
	 */
	private final ContainerConverter containerConverter = new ContainerConverter();

	/**
	 * Utility to convert maps to byte arrays.
	 */
	private final MapBytesUtility mapBytesUtility = new MapBytesUtility();

	/**
	 * Leader selector to elect admin server that will handle stream deployment requests. Marked volatile because this
	 * reference is written and read by the Curator event dispatch threads - there is no guarantee that the same thread
	 * will do the reading and writing.
	 */
	private volatile LeaderSelector leaderSelector;

	/**
	 * Listener that is invoked when this admin server is elected leader.
	 */
	private final LeaderSelectorListener leaderListener = new LeaderListener();

	private final ConnectionListener connectionListener = new ConnectionListener();

	public AdminServer(ZooKeeperConnection zkConnection,
			StreamDefinitionRepository streamDefinitionRepository,
			ModuleDefinitionRepository moduleDefinitionRepository,
			ModuleOptionsMetadataResolver moduleOptionsMetadataResolver) {
		Assert.notNull(zkConnection, "ZooKeeperConnection must not be null");
		Assert.notNull(streamDefinitionRepository, "StreamDefinitionRepository must not be null");
		Assert.notNull(moduleDefinitionRepository, "ModuleDefinitionRepository must not be null");
		Assert.notNull(moduleOptionsMetadataResolver, "moduleOptionsMetadataResolver must not be null");
		this.zkConnection = zkConnection;
		this.streamDefinitionRepository = streamDefinitionRepository;
		this.moduleDefinitionRepository = moduleDefinitionRepository;
		this.moduleOptionsMetadataResolver = moduleOptionsMetadataResolver;
	}

	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		this.applicationContext = event.getApplicationContext();
		if (this.zkConnection.isConnected()) {
			requestLeadership(this.zkConnection.getClient());
		}
		this.zkConnection.addListener(connectionListener);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Iterator<Container> getContainerIterator() {
		return new ChildPathIterator<Container>(containerConverter, containers);
	}

	private String getId() {
		return this.applicationContext.getId();
	}

	/**
	 * Register the {@link LeaderListener} if not already registered. This method is {@code synchronized} because it may
	 * be invoked either by the thread starting the {@link ApplicationContext} or the thread that raises the ZooKeeper
	 * connection event.
	 * 
	 * @param client the {@link CuratorFramework} client
	 */
	private synchronized void requestLeadership(CuratorFramework client) {
		try {
			Paths.ensurePath(client, Paths.DEPLOYMENTS);
			Paths.ensurePath(client, Paths.CONTAINERS);
			Paths.ensurePath(client, Paths.STREAMS);
			Paths.ensurePath(client, Paths.JOBS);

			if (leaderSelector == null) {
				leaderSelector = new LeaderSelector(client, Paths.build(Paths.ADMIN), leaderListener);
				leaderSelector.setId(getId());
				leaderSelector.start();
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private class ConnectionListener implements ZooKeeperConnectionListener {

		@Override
		public void onConnect(CuratorFramework client) {
			LOG.info("Admin {} CONNECTED", getId());
			requestLeadership(client);
		}

		@Override
		public void onDisconnect(CuratorFramework client) {
			leaderSelector = null;
		}
	}

	/**
	 * Converts a {@link org.apache.curator.framework.recipes.cache.ChildData} node to a {@link xdzk.cluster.Container}.
	 */
	public class ContainerConverter implements Converter<ChildData, Container> {

		@Override
		public Container convert(ChildData source) {
			// This converter will be invoked upon every iteration of the
			// iterator returned by getContainerIterator. While elegant,
			// this isn't exactly efficient. TODO - revisit
			return new Container(Paths.stripPath(source.getPath()), mapBytesUtility.toMap(source.getData()));
		}
	}

	/**
	 * Listener implementation that is invoked when this server becomes the leader.
	 */
	class LeaderListener extends LeaderSelectorListenerAdapter {

		@Override
		public void takeLeadership(CuratorFramework client) throws Exception {
			LOG.info("Leader Admin {} is watching for stream deployment requests.", getId());

			PathChildrenCacheListener streamListener = null;
			PathChildrenCacheListener jobListener = null;
			PathChildrenCacheListener containerListener = null;
			try {
				streamListener = new StreamListener(AdminServer.this,
						streamDefinitionRepository,
						moduleDefinitionRepository,
						moduleOptionsMetadataResolver);

				streams = new PathChildrenCache(client, Paths.STREAMS, true);
				streams.getListenable().addListener(streamListener);
				streams.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);

				jobListener = new JobListener(AdminServer.this, moduleDefinitionRepository,
						moduleOptionsMetadataResolver);

				jobs = new PathChildrenCache(client, Paths.JOBS, true);
				jobs.getListenable().addListener(jobListener);
				jobs.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);

				containerListener = new ContainerListener(AdminServer.this,
						streamDefinitionRepository,
						moduleDefinitionRepository,
						moduleOptionsMetadataResolver, streams);

				containers = new PathChildrenCache(client, Paths.CONTAINERS, true);
				containers.getListenable().addListener(containerListener);
				containers.start();

				Thread.sleep(Long.MAX_VALUE);
			}
			catch (InterruptedException e) {
				LOG.info("Leadership canceled due to thread interrupt");
				Thread.currentThread().interrupt();
			}
			finally {
				if (containerListener != null) {
					containers.getListenable().removeListener(containerListener);
				}
				containers.close();

				if (streamListener != null) {
					streams.getListenable().removeListener(streamListener);
				}
				streams.close();

				if (jobListener != null) {
					jobs.getListenable().removeListener(jobListener);
				}
				jobs.close();
			}
		}
	}

}