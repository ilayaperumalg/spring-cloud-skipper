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
import java.util.List;
import java.util.Map;

import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationManifestUtils;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.Docker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.resource.support.DelegatingResourceLoader;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.skipper.SkipperException;
import org.springframework.cloud.skipper.deployer.cloudfoundry.PlatformCloudFoundryOperations;
import org.springframework.cloud.skipper.domain.CFApplicationManifestReader;
import org.springframework.cloud.skipper.domain.CFApplicationSkipperManifest;
import org.springframework.cloud.skipper.domain.CFApplicationSpec;
import org.springframework.cloud.skipper.domain.Release;
import org.springframework.cloud.skipper.domain.SpringCloudDeployerApplicationManifestReader;
import org.springframework.cloud.skipper.domain.Status;
import org.springframework.cloud.skipper.domain.StatusCode;
import org.springframework.cloud.skipper.server.deployer.AppDeploymentRequestFactory;
import org.springframework.cloud.skipper.server.domain.AppDeployerData;
import org.springframework.cloud.skipper.server.repository.DeployerRepository;
import org.springframework.cloud.skipper.server.repository.ReleaseRepository;
import org.springframework.core.io.Resource;

/**
 * Responsible for deleting the provided list of applications and updating the status of
 * the release.
 * @author Mark Pollack
 */
public class DeleteStep {

	private final Logger logger = LoggerFactory.getLogger(DeleteStep.class);

	private final ReleaseRepository releaseRepository;

	private final DeployerRepository deployerRepository;

	private final SpringCloudDeployerApplicationManifestReader applicationManifestReader;

	private final CFApplicationManifestReader cfApplicationManifestReader;

	private final PlatformCloudFoundryOperations platformCloudFoundryOperations;

	private final DelegatingResourceLoader delegatingResourceLoader;

	public DeleteStep(ReleaseRepository releaseRepository, DeployerRepository deployerRepository,
			SpringCloudDeployerApplicationManifestReader applicationManifestReader,
			CFApplicationManifestReader cfApplicationManifestReader,
			PlatformCloudFoundryOperations platformCloudFoundryOperations,
			DelegatingResourceLoader delegatingResourceLoader) {
		this.releaseRepository = releaseRepository;
		this.deployerRepository = deployerRepository;
		this.applicationManifestReader = applicationManifestReader;
		this.cfApplicationManifestReader = cfApplicationManifestReader;
		this.platformCloudFoundryOperations = platformCloudFoundryOperations;
		this.delegatingResourceLoader = delegatingResourceLoader;
	}

	public Release delete(Release release, AppDeployerData existingAppDeployerData,
			List<String> applicationNamesToDelete) {

		String releaseManifest = release.getManifest().getData();
		if (this.applicationManifestReader.assertSupportedKinds(releaseManifest)) {
			AppDeployer appDeployer = this.deployerRepository.findByNameRequired(release.getPlatformName())
					.getAppDeployer();

			Map<String, String> appNamesAndDeploymentIds = existingAppDeployerData.getDeploymentDataAsMap();

			for (Map.Entry<String, String> appNameAndDeploymentId : appNamesAndDeploymentIds.entrySet()) {
				if (applicationNamesToDelete.contains(appNameAndDeploymentId.getKey())) {
					AppStatus appStatus = appDeployer.status(appNameAndDeploymentId.getValue());
					if (appStatus.getState().equals(DeploymentState.deployed)) {
						appDeployer.undeploy(appNameAndDeploymentId.getValue());
					}
					else {
						logger.warn("For Release name {}, did not undeploy existing app {} as its status is not "
								+ "'deployed'.", release.getName(), appNameAndDeploymentId.getKey());
					}
				}
			}
		}
		else if (this.cfApplicationManifestReader.assertSupportedKinds(releaseManifest)) {
			ApplicationManifest applicationManifest = getApplicationManifest(release);
			String applicationName = applicationManifest.getName();
			DeleteApplicationRequest deleteApplicationRequest = DeleteApplicationRequest.builder().name(applicationName)
					.build();
			this.platformCloudFoundryOperations.getCloudFoundryOperations(release.getPlatformName()).applications()
					.delete(deleteApplicationRequest)
					.doOnSuccess(v -> logger.info("Successfully undeployed app {}", applicationName))
					.doOnError(e -> logger.error("Failed to undeploy app %s", applicationName))
					.block();
		}

		Status deletedStatus = new Status();
		deletedStatus.setStatusCode(StatusCode.DELETED);
		release.getInfo().setStatus(deletedStatus);
		release.getInfo().setDescription("Delete complete");
		this.releaseRepository.save(release);
		return release;
	}

	public ApplicationManifest getApplicationManifest(Release release) {
		List<? extends CFApplicationSkipperManifest> cfApplicationManifestList = this.cfApplicationManifestReader
				.read(release.getManifest()
						.getData());
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
}
