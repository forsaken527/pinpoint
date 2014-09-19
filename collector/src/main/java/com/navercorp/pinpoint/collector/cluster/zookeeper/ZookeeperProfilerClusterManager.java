package com.nhn.pinpoint.collector.cluster.zookeeper;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhn.pinpoint.collector.cluster.ProfilerClusterPoint;
import com.nhn.pinpoint.collector.cluster.WorkerState;
import com.nhn.pinpoint.collector.cluster.WorkerStateContext;
import com.nhn.pinpoint.collector.cluster.zookeeper.exception.PinpointZookeeperException;
import com.nhn.pinpoint.collector.cluster.zookeeper.job.DeleteJob;
import com.nhn.pinpoint.collector.cluster.zookeeper.job.UpdateJob;
import com.nhn.pinpoint.collector.receiver.tcp.AgentPropertiesType;
import com.nhn.pinpoint.rpc.server.ChannelContext;
import com.nhn.pinpoint.rpc.server.PinpointServerSocketStateCode;
import com.nhn.pinpoint.rpc.server.SocketChannelStateChangeEventListener;
import com.nhn.pinpoint.rpc.util.MapUtils;

/**
 * @author koo.taejin <kr14910>
 */
public class ZookeeperProfilerClusterManager implements SocketChannelStateChangeEventListener  {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private final ZookeeperClient client;
	private final ZookeeperLatestJobWorker worker;

	private final WorkerStateContext workerState;

	private final ProfilerClusterPoint clusterPoint;
	
	private final ObjectMapper objectmapper = new ObjectMapper();

	// 단순하게 하자 그냥 RUN이면 등록 FINISHED면 경우 삭제 그외 skip
	// 만약 상태가 안맞으면(?) 보정 들어가야 하는데 leak detector 같은걸 worker내부에 둘 까도 고민중 
	//
	// RUN_DUPLEX에서만 생성할수 있게 해야한다.
	// 지금은 RUN 상대방의 상태를 알수 없는 상태이기 때문에 이상황에서 등록
	public ZookeeperProfilerClusterManager(ZookeeperClient client, String serverIdentifier, ProfilerClusterPoint clusterPoint) {
		this.workerState = new WorkerStateContext();
		this.clusterPoint = clusterPoint;
		
		this.client = client;
		this.worker = new ZookeeperLatestJobWorker(client, serverIdentifier);
	}

	public void start() {
		switch (this.workerState.getCurrentState()) {
			case NEW:
				if (this.workerState.changeStateInitializing()) {
					logger.info("{} initialization started.", this.getClass().getSimpleName());
	
					if (worker != null) {
						worker.start();
					}
	
					workerState.changeStateStarted();
					logger.info("{} initialization completed.", this.getClass().getSimpleName());
					
					break;
				}
			case INITIALIZING:
				logger.info("{} already initializing.", this.getClass().getSimpleName());
				break;
			case STARTED:
				logger.info("{} already started.", this.getClass().getSimpleName());
				break;
			case DESTROYING:
				throw new IllegalStateException("Already destroying.");
			case STOPPED:
				throw new IllegalStateException("Already stopped.");
			case ILLEGAL_STATE:
				throw new IllegalStateException("Invalid State.");
		}		
	}
	
	public void stop() {
		if (!(this.workerState.changeStateDestroying())) {
			WorkerState state = this.workerState.getCurrentState();
			
			logger.info("{} already {}.", this.getClass().getSimpleName(), state.toString());
			return;
		}

		logger.info("{} destorying started.", this.getClass().getSimpleName());

		if (worker != null) {
			worker.stop();
		}

		this.workerState.changeStateStoped();
		logger.info("{} destorying completed.", this.getClass().getSimpleName());

		return;

	}
	
	@Override
	public void eventPerformed(ChannelContext channelContext, PinpointServerSocketStateCode stateCode) {
		if (workerState.isStarted()) {
			logger.info("eventPerformed ChannelContext={}, State={}", channelContext, stateCode);

			Map agentProperties = channelContext.getChannelProperties();
			
			// 현재는 AgentProperties에 값을 모를 경우 skip 
			if (skipAgent(agentProperties)) {
				return;
			}
			
			if (PinpointServerSocketStateCode.RUN_DUPLEX_COMMUNICATION == stateCode) {
				byte[] contents = serializeContents(agentProperties, stateCode);
				if (contents == null) {
					return;
				}
				
				UpdateJob job = new UpdateJob(channelContext, contents);
				worker.putJob(job);
				
				clusterPoint.registerChannelContext(channelContext);
			} else if (PinpointServerSocketStateCode.isFinished(stateCode)) {
				DeleteJob job = new DeleteJob(channelContext);
				worker.putJob(job);
				
				clusterPoint.unregisterChannelContext(channelContext);
			} 
		} else {
			WorkerState state = this.workerState.getCurrentState();
			logger.info("{} invalid state {}.", this.getClass().getSimpleName(), state.toString());
			return;
		}
		
	}
	
	public Map getData(ChannelContext channelContext) {
		byte[] contents = worker.getData(channelContext);
		
		if (contents == null) {
			return Collections.emptyMap();
		}
		
		return deserializeContents(contents);
	}
	
	public List<String> getChildrenNode(String path, boolean watch) throws PinpointZookeeperException, InterruptedException {
		if (client.exists(path)) {
			return client.getChildrenNode(path, watch);
		} else {
			client.createPath(path);
			return client.getChildrenNode(path, watch);
		}
	}
	
	public List<ChannelContext> getRegisteredChannelContextList() {
		return worker.getRegisteredChannelContextList();
	}

	private boolean skipAgent(Map<Object, Object> agentProperties) {
		String applicationName = MapUtils.getString(agentProperties, AgentPropertiesType.APPLICATION_NAME.getName());
		String agentId = MapUtils.getString(agentProperties, AgentPropertiesType.AGENT_ID.getName());

		if (StringUtils.isEmpty(applicationName) || StringUtils.isEmpty(agentId)) {
			return true;
		}

		return false;
	}
	
	private byte[] serializeContents(Map agentProperties, PinpointServerSocketStateCode state) {
		Map<Object, Object> contents = new HashMap<Object, Object>();
		contents.put("agent", agentProperties);
		contents.put("state", state.name());
		
		try {
			return objectmapper.writeValueAsBytes(contents);
		} catch (JsonProcessingException e) {
			logger.warn(e.getMessage(), e);
		}
		
		return null;
	}

	private Map deserializeContents(byte[] contents) {
		try {
			return objectmapper.readValue(contents, Map.class);
		} catch (Exception e) {
			logger.warn(e.getMessage(), e);
		}

		return Collections.emptyMap();
	}

}