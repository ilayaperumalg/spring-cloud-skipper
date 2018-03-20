/*
 * Copyright 2017 the original author or authors.
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
package org.springframework.cloud.skipper.server.deployer.strategies;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationManifestUtils;
import org.cloudfoundry.operations.applications.Docker;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.InstanceDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.resource.support.DelegatingResourceLoader;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryAppInstanceStatus;
import org.springframework.cloud.skipper.SkipperException;
import org.springframework.cloud.skipper.deployer.cloudfoundry.PlatformCloudFoundryOperations;
import org.springframework.cloud.skipper.domain.CFApplicationManifestReader;
import org.springframework.cloud.skipper.domain.CFApplicationSkipperManifest;
import org.springframework.cloud.skipper.domain.CFApplicationSpec;
import org.springframework.cloud.skipper.domain.Release;
import org.springframework.cloud.skipper.domain.SpringCloudDeployerApplicationManifestReader;
import org.springframework.cloud.skipper.server.deployer.AppDeploymentRequestFactory;
import org.springframework.cloud.skipper.server.domain.AppDeployerData;
import org.springframework.cloud.skipper.server.repository.AppDeployerDataRepository;
import org.springframework.cloud.skipper.server.repository.DeployerRepository;
import org.springframework.core.io.Resource;

/**
 * Checks if the apps in the Replacing release are healthy. Health polling values are set
 * using {@link HealthCheckProperties}
 * @author Mark Pollack
 * @author Ilayaperumal Gopinathan
 */
public class HealthCheckStep {

	private final Logger logger = LoggerFactory.getLogger(HealthCheckStep.class);

	private final AppDeployerDataRepository appDeployerDataRepository;

	private final HealthCheckProperties healthCheckProperties;

	private final DeployerRepository deployerRepository;

	private final SpringCloudDeployerApplicationManifestReader applicationManifestReader;

	private final CFApplicationManifestReader cfApplicationManifestReader;

	private final PlatformCloudFoundryOperations platformCloudFoundryOperations;

	private final DelegatingResourceLoader delegatingResourceLoader;

	public HealthCheckStep(AppDeployerDataRepository appDeployerDataRepository,
			DeployerRepository deployerRepository,
			HealthCheckProperties healthCheckProperties,
			SpringCloudDeployerApplicationManifestReader applicationManifestReader,
			CFApplicationManifestReader cfApplicationManifestReader,
			PlatformCloudFoundryOperations platformCloudFoundryOperations,
			DelegatingResourceLoader delegatingResourceLoader) {
		this.appDeployerDataRepository = appDeployerDataRepository;
		this.deployerRepository = deployerRepository;
		this.healthCheckProperties = healthCheckProperties;
		this.applicationManifestReader = applicationManifestReader;
		this.cfApplicationManifestReader = cfApplicationManifestReader;
		this.platformCloudFoundryOperations = platformCloudFoundryOperations;
		this.delegatingResourceLoader = delegatingResourceLoader;
	}

	public boolean isHealthy(Release replacingRelease) {
		String releaseManifest = replacingRelease.getManifest().getData();
		if (this.applicationManifestReader.assertSupportedKinds(releaseManifest)) {
			AppDeployerData replacingAppDeployerData = this.appDeployerDataRepository
					.findByReleaseNameAndReleaseVersionRequired(
							replacingRelease.getName(), replacingRelease.getVersion());
			Map<String, String> appNamesAndDeploymentIds = replacingAppDeployerData.getDeploymentDataAsMap();
			AppDeployer appDeployer = this.deployerRepository
					.findByNameRequired(replacingRelease.getPlatformName())
					.getAppDeployer();
			logger.debug("Getting status for apps in replacing release {}-v{}", replacingRelease.getName(),
					replacingRelease.getVersion());
			for (Map.Entry<String, String> appNameAndDeploymentId : appNamesAndDeploymentIds.entrySet()) {
				AppStatus status = appDeployer.status(appNameAndDeploymentId.getValue());
				if (status.getState() == DeploymentState.deployed) {
					return true;
				}
			}
		}
		else if (this.cfApplicationManifestReader.assertSupportedKinds(releaseManifest)) {
			ApplicationManifest applicationManifest = getApplicationManifest(replacingRelease);
			String applicationName = applicationManifest.getName();
			AppStatus appStatus = getStatus(replacingRelease, applicationManifest)
					.doOnSuccess(v -> logger.info("Successfully computed status [{}] for {}", v,
							applicationName))
					.doOnError(e -> logger.error(
							String.format("Failed to compute status for %s", applicationName)))
					.block();
			if (appStatus.getState() == DeploymentState.deployed) {
				return true;
			}
		}
		return false;
	}

	public Release status(Release release) {
		ApplicationManifest applicationManifest = getApplicationManifest(release);
		String applicationName = applicationManifest.getName();
		AppStatus appStatus = null;
		try {
			appStatus = getStatus(release, applicationManifest)
					.doOnSuccess(v -> logger.info("Successfully computed status [{}] for {}", v,
							applicationName))
					.doOnError(e -> logger.error(
							String.format("Failed to compute status for %s", applicationName)))
					.block();
		}
		catch (Exception timeoutDueToBlock) {
			logger.error("Caught exception while querying for status of {}", applicationName, timeoutDueToBlock);
			appStatus = createErrorAppStatus(applicationName);
		}
		release.getInfo().getStatus().setPlatformStatusAsAppStatusList(Collections.singletonList(appStatus));
		return release;
	}

	public ApplicationManifest getApplicationManifest(Release release) {
		List<? extends CFApplicationSkipperManifest> cfApplicationManifestList = this.cfApplicationManifestReader
				.read(release.getManifest().getData());
		for (CFApplicationSkipperManifest cfApplicationSkipperManifest : cfApplicationManifestList) {
			CFApplicationSpec spec = cfApplicationSkipperManifest.getSpec();
			try {
				Resource application = this.delegatingResourceLoader.getResource(
						AppDeploymentRequestFactory.getResourceLocation(spec.getResource(), spec.getVersion()));
				Resource manifest = this.delegatingResourceLoader.getResource(spec.getManifest());
				File manifestYaml = manifest.getFile();
				List<ApplicationManifest> applicationManifestList = ApplicationManifestUtils
						.read(manifestYaml.toPath());
				// todo: support multiple application manifest
				if (applicationManifestList.size() > 1) {
					throw new IllegalArgumentException("Multiple manifest YAML entries are not supported yet");
				}
				ApplicationManifest applicationManifest = applicationManifestList.get(0);
				ApplicationManifest.Builder applicationManifestBuilder = ApplicationManifest.builder()
						.from(applicationManifest);
				if (!applicationManifest.getName().endsWith("-v" + release.getVersion())) {
					applicationManifestBuilder
							.name(String.format("%s-v%s", applicationManifest.getName(), release.getVersion()));
				}
				if (application != null && application instanceof DockerResource) {
					String uriString = application.getURI().toString();
					applicationManifestBuilder.docker(
							Docker.builder().image(uriString.substring(uriString.indexOf("docker:"))).build());
				}
				else {
					applicationManifestBuilder.path(application.getFile().toPath());
				}
				return applicationManifestBuilder.build();
			}
			catch (Exception e) {
				throw new SkipperException(e.getMessage());
			}
		}
		return null;
	}

	private Mono<AppStatus> getStatus(Release release, ApplicationManifest applicationManifest) {
		String applicationName = applicationManifest.getName();
		GetApplicationRequest getApplicationRequest = GetApplicationRequest.builder().name(applicationName).build();
		return this.platformCloudFoundryOperations.getCloudFoundryOperations(release.getPlatformName())
				.applications().get(getApplicationRequest)
				.map(applicationDetail -> createAppStatus(applicationDetail, applicationName))
				.onErrorResume(IllegalArgumentException.class, t -> {
					logger.debug("Application for {} does not exist.", applicationName);
					return Mono.just(createEmptyAppStatus(applicationName));
				})
				.onErrorResume(Throwable.class, t -> {
					logger.error("Error: " + t);
					return Mono.just(createErrorAppStatus(applicationName));
				});
	}

	private AppStatus createAppStatus(ApplicationDetail applicationDetail, String deploymentId) {
		logger.trace("Gathering instances for " + applicationDetail);
		logger.trace("InstanceDetails: " + applicationDetail.getInstanceDetails());

		AppStatus.Builder builder = AppStatus.of(deploymentId);

		int i = 0;
		for (InstanceDetail instanceDetail : applicationDetail.getInstanceDetails()) {
			builder.with(new CloudFoundryAppInstanceStatus(applicationDetail, instanceDetail, i++));
		}
		for (; i < applicationDetail.getInstances(); i++) {
			builder.with(new CloudFoundryAppInstanceStatus(applicationDetail, null, i));
		}

		return builder.build();
	}

	private AppStatus createEmptyAppStatus(String deploymentId) {
		return AppStatus.of(deploymentId)
				.build();
	}

	private AppStatus createErrorAppStatus(String deploymentId) {
		return AppStatus.of(deploymentId)
				.generalState(DeploymentState.error)
				.build();
	}
}
