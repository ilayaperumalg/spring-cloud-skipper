/*
 * Copyright 2018 the original author or authors.
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
package org.springframework.cloud.skipper.server.deployer;

import java.time.Duration;
import java.util.List;

import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.InstanceDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.resource.support.DelegatingResourceLoader;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryAppInstanceStatus;
import org.springframework.cloud.skipper.SkipperException;
import org.springframework.cloud.skipper.deployer.cloudfoundry.PlatformCloudFoundryOperations;
import org.springframework.cloud.skipper.domain.CFApplicationManifestReader;
import org.springframework.cloud.skipper.domain.CFApplicationSkipperManifest;
import org.springframework.cloud.skipper.domain.CFApplicationSpec;
import org.springframework.cloud.skipper.domain.Release;
import org.springframework.cloud.skipper.domain.Status;
import org.springframework.cloud.skipper.domain.StatusCode;
import org.springframework.core.io.Resource;

public class CFApplicationDeployer {

	private static final Logger logger = LoggerFactory.getLogger(CFApplicationDeployer.class);

	private final CFApplicationManifestReader cfApplicationManifestReader;

	private final PlatformCloudFoundryOperations platformCloudFoundryOperations;

	private final DelegatingResourceLoader delegatingResourceLoader;

	public CFApplicationDeployer(CFApplicationManifestReader cfApplicationManifestReader,
			PlatformCloudFoundryOperations platformCloudFoundryOperations,
			DelegatingResourceLoader delegatingResourceLoader) {
		this.cfApplicationManifestReader = cfApplicationManifestReader;
		this.platformCloudFoundryOperations = platformCloudFoundryOperations;
		this.delegatingResourceLoader = delegatingResourceLoader;
	}

	public ApplicationManifest getCFApplicationManifest(Release release) {
		ApplicationManifest applicationManifest = null;
		List<? extends CFApplicationSkipperManifest> cfApplicationManifestList = this.cfApplicationManifestReader
				.read(release.getManifest()
						.getData());
		for (CFApplicationSkipperManifest cfApplicationSkipperManifest : cfApplicationManifestList) {
			CFApplicationSpec spec = cfApplicationSkipperManifest.getSpec();
			try {
				Resource application = this.delegatingResourceLoader.getResource(
						AppDeploymentRequestFactory.getResourceLocation(spec.getResource(), spec.getVersion()));
				ApplicationManifest cfApplicationManifest = CFApplicationManifestUtils.getCFManifest(release);
				applicationManifest = CFApplicationManifestUtils
						.updateApplicationPath(cfApplicationManifest, application);
			}
			catch (Exception e) {
				throw new SkipperException(e.getMessage());
			}
		}
		return applicationManifest;
	}

	public Mono<AppStatus> getStatus(String applicationName, String platformName) {
		GetApplicationRequest getApplicationRequest = GetApplicationRequest.builder().name(applicationName).build();
		return this.platformCloudFoundryOperations.getCloudFoundryOperations(platformName)
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

	public AppStatus status(Release release) {
		logger.info("Checking application status for the release: " + release.getName());
		ApplicationManifest applicationManifest = CFApplicationManifestUtils.getCFManifest(release);
		String applicationName = applicationManifest.getName();
		AppStatus appStatus = null;
		try {
			appStatus = getStatus(applicationName, release.getPlatformName())
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
		return appStatus;
	}


	public Release delete(Release release) {
		ApplicationManifest applicationManifest = CFApplicationManifestUtils.getCFManifest(release);
		String applicationName = applicationManifest.getName();
		DeleteApplicationRequest deleteApplicationRequest = DeleteApplicationRequest.builder().name(applicationName)
				.build();
		this.platformCloudFoundryOperations.getCloudFoundryOperations(release.getPlatformName()).applications()
				.delete(deleteApplicationRequest)
				.timeout(Duration.ofSeconds(30L))
				.doOnSuccess(v -> logger.info("Successfully undeployed app {}", applicationName))
				.doOnError(e -> logger.error("Failed to undeploy app %s", applicationName))
				.block();
		Status deletedStatus = new Status();
		deletedStatus.setStatusCode(StatusCode.DELETED);
		release.getInfo().setStatus(deletedStatus);
		release.getInfo().setDescription("Delete complete");
		return release;
	}

}
