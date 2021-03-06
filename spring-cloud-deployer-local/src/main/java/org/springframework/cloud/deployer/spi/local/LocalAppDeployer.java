/*
 * Copyright 2016-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.local;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.util.SocketUtils;
import org.springframework.util.StringUtils;

/**
 * An {@link AppDeployer} implementation that spins off a new JVM process per app instance.
 *
 * @author Eric Bottard
 * @author Marius Bogoevici
 * @author Mark Fisher
 * @author Ilayaperumal Gopinathan
 * @author Janne Valkealahti
 * @author Patrick Peralta
 * @author Thomas Risberg
 * @author Oleg Zhurakousky
 */
public class LocalAppDeployer extends AbstractLocalDeployerSupport implements AppDeployer {

	private Path logPathRoot;

	private static final Logger logger = LoggerFactory.getLogger(LocalAppDeployer.class);

	protected static final String SERVER_PORT_KEY = "server.port";

	private static final String JMX_DEFAULT_DOMAIN_KEY = "spring.jmx.default-domain";

	private static final String ENDPOINTS_SHUTDOWN_ENABLED_KEY = "endpoints.shutdown.enabled";

	private static final int DEFAULT_SERVER_PORT = 8080;

	private final Map<String, List<AppInstance>> running = new ConcurrentHashMap<>();

	/**
	 * Instantiates a new local app deployer.
	 *
	 * @param properties the properties
	 */
	public LocalAppDeployer(LocalDeployerProperties properties) {
		super(properties);
		try {
			this.logPathRoot = Files.createTempDirectory(properties.getWorkingDirectoriesRoot(), "spring-cloud-dataflow-");
		}
		catch (IOException e) {
			throw new RuntimeException("Could not create workdir root: " + properties.getWorkingDirectoriesRoot(), e);
		}
	}

	@Override
	public String deploy(AppDeploymentRequest request) {
		String group = request.getDeploymentProperties().get(GROUP_PROPERTY_KEY);
		String deploymentId = String.format("%s.%s", group, request.getDefinition().getName());
		DeploymentState state = status(deploymentId).getState();
		if (state != DeploymentState.unknown) {
			throw new IllegalStateException(String.format("App with deploymentId [%s] is already deployed with state [%s]",
					deploymentId, state));
		}
		List<AppInstance> processes = new ArrayList<>();
		running.put(deploymentId, processes);
		boolean useDynamicPort = !request.getDefinition().getProperties().containsKey(SERVER_PORT_KEY);
		HashMap<String, String> args = new HashMap<>();
		args.putAll(request.getDefinition().getProperties());

		args.put(JMX_DEFAULT_DOMAIN_KEY, deploymentId);
		if (!request.getDefinition().getProperties().containsKey(ENDPOINTS_SHUTDOWN_ENABLED_KEY)) {
			args.put(ENDPOINTS_SHUTDOWN_ENABLED_KEY, "true");
		}
		args.put("endpoints.jmx.unique-names", "true");
		if (group != null) {
			args.put("spring.cloud.application.group", group);
		}
		try {
			Path deploymentGroupDir = Paths.get(logPathRoot.toFile().getAbsolutePath(),
					group + "-" + System.currentTimeMillis());
			if (!Files.exists(deploymentGroupDir)) {
				Files.createDirectory(deploymentGroupDir);
				deploymentGroupDir.toFile().deleteOnExit();
			}
			Path workDir = Files
					.createDirectory(Paths.get(deploymentGroupDir.toFile().getAbsolutePath(), deploymentId));
			if (getLocalDeployerProperties().isDeleteFilesOnExit()) {
				workDir.toFile().deleteOnExit();
			}
			String countProperty = request.getDeploymentProperties().get(COUNT_PROPERTY_KEY);
			int count = (StringUtils.hasText(countProperty)) ? Integer.parseInt(countProperty) : 1;
			for (int i = 0; i < count; i++) {
				int port = useDynamicPort ? SocketUtils.findAvailableTcpPort(DEFAULT_SERVER_PORT)
						: Integer.parseInt(request.getDefinition().getProperties().get(SERVER_PORT_KEY));
				if (useDynamicPort) {
					args.put(SERVER_PORT_KEY, String.valueOf(port));
				}
				Map<String, String> appInstanceEnv = new HashMap<>();
				AppInstance instance = new AppInstance(deploymentId, i, appInstanceEnv, port);
				ProcessBuilder builder = buildProcessBuilder(request, appInstanceEnv, args, Optional.of(i), deploymentId).inheritIO();

				builder.directory(workDir.toFile());
				if (this.shouldInheritLogging(request)){
					instance.start(builder, workDir);
					logger.info("Deploying app with deploymentId {} instance {}.\n   Logs will be inherited.", deploymentId, i);
				}
				else {
					instance.start(builder, workDir, getLocalDeployerProperties().isDeleteFilesOnExit());
					logger.info("Deploying app with deploymentId {} instance {}.\n   Logs will be in {}", deploymentId, i, workDir);
				}
				processes.add(instance);
			}
		}
		catch (IOException e) {
			throw new RuntimeException("Exception trying to deploy " + request, e);
		}
		return deploymentId;
	}
	
	@Override
	public void undeploy(String id) {
		List<AppInstance> processes = running.get(id);
		if (processes != null) {
			for (AppInstance instance : processes) {
				if (isAlive(instance.getProcess())) {
					logger.info("Un-deploying app with deploymentId {} instance {}.", id, instance.getInstanceNumber());
					shutdownAndWait(instance);
				}
			}
			running.remove(id);
		}
		else {
			throw new IllegalStateException(String.format("App with deploymentId %s is not in a deployed state.", id));
		}
	}

	@Override
	public AppStatus status(String id) {
		List<AppInstance> instances = running.get(id);
		AppStatus.Builder builder = AppStatus.of(id);
		if (instances != null) {
			for (AppInstance instance : instances) {
				builder.with(instance);
			}
		}
		AppStatus status = builder.build();
		return status;
	}

	@Override
	public RuntimeEnvironmentInfo environmentInfo() {
		return super.createRuntimeEnvironmentInfo(AppDeployer.class, this.getClass());
	}

	@PreDestroy
	public void shutdown() throws Exception {
		for (String deploymentId : running.keySet()) {
			undeploy(deploymentId);
		}
	}
	
	protected String buildRemoteDebugInstruction(Map<String, String> deploymentProperties, String deploymentId,
			int instanceIndex, int port) {
		String ds = deploymentProperties.getOrDefault(LocalDeployerProperties.DEBUG_SUSPEND, "y");
		StringBuilder debugCommandBuilder = new StringBuilder();
		String debugCommand;
		logger.warn("Deploying app with deploymentId {}, instance {}. Remote debugging is enabled on port {}.",
				deploymentId, instanceIndex, port);
		debugCommandBuilder.append("-agentlib:jdwp=transport=dt_socket,server=y,suspend=");
		debugCommandBuilder.append(ds.trim());
		debugCommandBuilder.append(",address=");
		debugCommandBuilder.append(port);
		debugCommand = debugCommandBuilder.toString();
		logger.debug("Deploying app with deploymentId {}, instance {}.  Debug Command = [{}]", debugCommand);
		if (ds.equals("y")) {
			logger.warn("Deploying app with deploymentId {}.  Application Startup will be suspended until remote "
					+ "debugging session is established.");
		}

		return debugCommand;
	}

	/**
	 * Will check if {@link LocalDeployerProperties#INHERIT_LOGGING} is set by
	 * checking deployment properties.
	 */
	private boolean shouldInheritLogging(AppDeploymentRequest request){
		boolean inheritLogging = false;
		if (request.getDeploymentProperties().containsKey(LocalDeployerProperties.INHERIT_LOGGING)){
			inheritLogging = Boolean.parseBoolean(request.getDeploymentProperties().get(LocalDeployerProperties.INHERIT_LOGGING));
		}
		return inheritLogging;
	}

	private static class AppInstance implements Instance, AppInstanceStatus {

		private final String deploymentId;

		private final int instanceNumber;

		private final URL baseUrl;

		private int pid;

		private Process process;

		private File workFile;

		private File stdout;

		private File stderr;

		private final Map<String, String> attributes = new TreeMap<>();

		private AppInstance(String deploymentId, int instanceNumber, Map<String, String> appInstanceEnv, int port) throws IOException {
			this.deploymentId = deploymentId;
			this.instanceNumber = instanceNumber;
			attributes.put("port", Integer.toString(port));
			attributes.put("guid", Integer.toString(port));
			this.baseUrl = new URL("http", Inet4Address.getLocalHost().getHostAddress(), port, "");
			attributes.put("url", baseUrl.toString());
			appInstanceEnv.put("INSTANCE_INDEX", Integer.toString(instanceNumber));
			appInstanceEnv.put("SPRING_APPLICATION_INDEX", Integer.toString(instanceNumber));
			appInstanceEnv.put("SPRING_CLOUD_APPLICATION_GUID", Integer.toString(port));
		}

		@Override
		public String getId() {
			return deploymentId + "-" + instanceNumber;
		}

		@Override
		public URL getBaseUrl() {
			return this.baseUrl;
		}

		@Override
		public Process getProcess() {
			return this.process;
		}

		@Override
		public String toString() {
			return String.format("%s [%s]", getId(), getState());
		}

		@Override
		public DeploymentState getState() {
			Integer exit = getProcessExitValue(process);
			// TODO: consider using exit code mapper concept from batch
			if (exit != null) {
				return DeploymentState.failed;
			}
			try {
				HttpURLConnection urlConnection = (HttpURLConnection) baseUrl.openConnection();
				urlConnection.setConnectTimeout(100);
				urlConnection.connect();
				urlConnection.disconnect();
				return DeploymentState.deployed;
			}
			catch (IOException e) {
				return DeploymentState.deploying;
			}
		}

		public int getInstanceNumber() {
			return instanceNumber;
		}

		@Override
		public Map<String, String> getAttributes() {
			return this.attributes;
		}

		/**
		 * Will start the process while redirecting 'out' and 'err' streams
		 * to the 'out' and 'err' streams of this process.
		 */
		private void start(ProcessBuilder builder, Path workDir) throws IOException {
			this.workFile = workDir.toFile();
			this.attributes.put("working.dir", this.workFile.getAbsolutePath());
			this.process = builder.start();
		    this.pid = getLocalProcessPid(this.process);
		    if (pid > 0) {
				// add pid if we got it
				attributes.put("pid", Integer.toString(pid));
			}
		}

		private void start(ProcessBuilder builder, Path workDir, boolean deleteOnExist) throws IOException {
			String workDirPath = workDir.toFile().getAbsolutePath();

			this.stdout = Files.createFile(Paths.get(workDirPath, "stdout_" + instanceNumber + ".log")).toFile();
			this.attributes.put("stdout", stdout.getAbsolutePath());

			this.stderr = Files.createFile(Paths.get(workDirPath, "stderr_" + instanceNumber + ".log")).toFile();
			this.attributes.put("stderr", stderr.getAbsolutePath());

			if (deleteOnExist) {
				this.stdout.deleteOnExit();
				this.stderr.deleteOnExit();
			}
			builder.redirectOutput(Redirect.to(this.stdout));
			builder.redirectError(Redirect.to(this.stderr));

			this.start(builder, workDir);
		}
	}

	/**
	 * Returns the process exit value. We explicitly use Integer instead of int
	 * to indicate that if {@code NULL} is returned, the process is still running.
	 * @param process the process
	 * @return the process exit value or {@code NULL} if process is still alive
	 */
	private static Integer getProcessExitValue(Process process) {
		try {
			return process.exitValue();
		}
		catch (IllegalThreadStateException e) {
			// process is still alive
			return null;
		}
	}

	/**
	 * Gets the local process pid if available. This should be a safe workaround
	 * for unix systems where reflection can be used to get pid. More reliable
	 * way should land with jdk9.
	 *
	 * @param p the process
	 * @return the local process pid
	 */
	private static synchronized int getLocalProcessPid(Process p) {
		int pid = 0;
		try {
			if (p.getClass().getName().equals("java.lang.UNIXProcess")) {
				Field f = p.getClass().getDeclaredField("pid");
				f.setAccessible(true);
				pid = f.getInt(p);
				f.setAccessible(false);
			}
		} catch (Exception e) {
			pid = 0;
		}
		return pid;
	}

}
