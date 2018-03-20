/*
 * Copyright 2017-2018 the original author or authors.
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.cloudfoundry.AbstractCloudFoundryException;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationManifestUtils;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.Docker;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.InstanceDetail;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.resource.support.DelegatingResourceLoader;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryAppInstanceStatus;
import org.springframework.cloud.skipper.SkipperException;
import org.springframework.cloud.skipper.deployer.cloudfoundry.PlatformCloudFoundryOperations;
import org.springframework.cloud.skipper.domain.CFApplicationManifestReader;
import org.springframework.cloud.skipper.domain.CFApplicationSkipperManifest;
import org.springframework.cloud.skipper.domain.CFApplicationSpec;
import org.springframework.cloud.skipper.domain.Manifest;
import org.springframework.cloud.skipper.domain.Release;
import org.springframework.cloud.skipper.domain.SkipperManifestKind;
import org.springframework.cloud.skipper.domain.SkipperManifestReader;
import org.springframework.cloud.skipper.domain.Status;
import org.springframework.cloud.skipper.domain.StatusCode;
import org.springframework.cloud.skipper.domain.deployer.ReleaseDifference;
import org.springframework.cloud.skipper.server.domain.AppDeployerData;
import org.springframework.cloud.skipper.server.repository.AppDeployerDataRepository;
import org.springframework.cloud.skipper.server.repository.DeployerRepository;
import org.springframework.cloud.skipper.server.repository.ReleaseRepository;
import org.springframework.cloud.skipper.server.util.ArgumentSanitizer;
import org.springframework.cloud.skipper.server.util.ManifestUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;

/**
 * A ReleaseManager implementation that uses an AppDeployer.
 *
 * @author Ilayaperumal Gopinathan
 */
@SuppressWarnings({ "unchecked", "deprecation" })
public class CFManifestDeployerReleaseManager implements ReleaseManager {

	public static final String SPRING_CLOUD_DEPLOYER_COUNT = "spring.cloud.deployer.count";

	private static final Logger logger = LoggerFactory.getLogger(CFManifestDeployerReleaseManager.class);

	private final ReleaseRepository releaseRepository;

	private final AppDeployerDataRepository appDeployerDataRepository;

	private final DeployerRepository deployerRepository;

	private final ReleaseAnalyzer releaseAnalyzer;

	private final CFApplicationManifestReader cfApplicationManifestReader;

	private final DelegatingResourceLoader delegatingResourceLoader;

	private final PlatformCloudFoundryOperations platformCloudFoundryOperations;

	public CFManifestDeployerReleaseManager(ReleaseRepository releaseRepository,
			AppDeployerDataRepository appDeployerDataRepository,
			DeployerRepository deployerRepository,
			ReleaseAnalyzer releaseAnalyzer,
			CFApplicationManifestReader cfApplicationManifestReader,
			DelegatingResourceLoader delegatingResourceLoader,
			PlatformCloudFoundryOperations platformCloudFoundryOperations) {
		this.releaseRepository = releaseRepository;
		this.appDeployerDataRepository = appDeployerDataRepository;
		this.deployerRepository = deployerRepository;
		this.releaseAnalyzer = releaseAnalyzer;
		this.cfApplicationManifestReader = cfApplicationManifestReader;
		this.delegatingResourceLoader = delegatingResourceLoader;
		this.platformCloudFoundryOperations = platformCloudFoundryOperations;
	}

	public SkipperManifestReader getManifestReader() {
		return this.cfApplicationManifestReader;
	}

	public String[] getSupportedManifestKinds() {
		return new String[] { SkipperManifestKind.CFApplication.name() };
	}

	public Release install(Release releaseInput) {
		Release release = this.releaseRepository.save(releaseInput);
		ApplicationManifest applicationManifest = getApplicationManifest(release);
		logger.debug("Manifest = " + ArgumentSanitizer.sanitizeYml(releaseInput.getManifest().getData()));
		// Deploy the application
		Map<String, String> appNameDeploymentIdMap = new HashMap<>();
		Yaml yaml = new Yaml();
		File manifestYaml = getCFManifestFile(release);
		try {
			appNameDeploymentIdMap
					.put(applicationManifest.getName(), yaml.dump(yaml.load(new FileInputStream(manifestYaml))));
		}
		catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
		this.platformCloudFoundryOperations.getCloudFoundryOperations(releaseInput.getPlatformName())
				.applications().pushManifest(
				PushApplicationManifestRequest.builder()
						.manifest(applicationManifest)
						.stagingTimeout(Duration.ofMinutes(15L))
						.startupTimeout(Duration.ofMinutes(5L))
						.build())
				.doOnSuccess(v -> logger.info("Done uploading bits for {}", applicationManifest.getName()))
				.doOnError(e -> logger.error(
						String.format("Error creating app %s.  Exception Message %s", applicationManifest.getName(),
								e.getMessage())))
				.timeout(Duration.ofSeconds(360L))
				.doOnTerminate((item, error) -> {
					if (error == null) {
						logger.info("Successfully deployed {}", applicationManifest.getName());
						AppDeployerData appDeployerData = new AppDeployerData();
						appDeployerData.setReleaseName(release.getName());
						appDeployerData.setReleaseVersion(release.getVersion());
						appDeployerData.setDeploymentDataUsingMap(appNameDeploymentIdMap);

						this.appDeployerDataRepository.save(appDeployerData);

						// Update Status in DB
						Status status = new Status();
						status.setStatusCode(StatusCode.DEPLOYED);
						release.getInfo().setStatus(status);
						release.getInfo().setDescription("Install complete");
					}
					else if (isNotFoundError().test(error)) {
						logger.warn("Unable to deploy application. It may have been destroyed before start completed: "
								+ error.getMessage());
					}
					else {
						logger.error(String.format("Failed to deploy %s", applicationManifest.getName()));
					}
				})
				.block();
		// Store updated state in in DB and compute status
		return status(this.releaseRepository.save(release));
	}

	public Predicate<Throwable> isNotFoundError() {
		return t -> t instanceof AbstractCloudFoundryException
				&& ((AbstractCloudFoundryException) t).getStatusCode() == HttpStatus.NOT_FOUND.value();
	}

	public File getCFManifestFile(Release release) {
		List<? extends CFApplicationSkipperManifest> cfApplicationManifestList = this.cfApplicationManifestReader
				.read(release.getManifest()
						.getData());
		for (CFApplicationSkipperManifest cfApplicationSkipperManifest : cfApplicationManifestList) {
			CFApplicationSpec spec = cfApplicationSkipperManifest.getSpec();
			try {
				Resource manifest = this.delegatingResourceLoader.getResource(spec.getManifest());
				return manifest.getFile();
			}
			catch (IOException e) {
				throw new IllegalArgumentException(e);
			}
		}
		return null;
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

	public List<CFApplicationSkipperManifest> getReleaseManifest(Release release) {
		return this.cfApplicationManifestReader.read(release.getManifest().getData());
	}

	@Override
	public ReleaseAnalysisReport createReport(Release existingRelease, Release replacingRelease) {
		List<CFApplicationSkipperManifest> existingReleaseManifest = getReleaseManifest(existingRelease);
		List<CFApplicationSkipperManifest> replacingReleaseManifest = getReleaseManifest(replacingRelease);
//		ApplicationManifest existingApplicationManifest = getApplicationManifest(existingRelease);
//		ApplicationManifest replacingApplicationManifest = getApplicationManifest(replacingRelease);
//		ApplicationManifestDifferenceFactory applicationManifestDifferenceFactory = new ApplicationManifestDifferenceFactory();
//		String existingApplicationName = existingApplicationManifest.getName();
//		String replacingApplicationName = replacingApplicationManifest.getName();
		ReleaseDifference releaseDifference = new ReleaseDifference();
		List<String> applicationNamesToUpgrade = Collections.singletonList("test");
		String manifestData = ManifestUtils.createManifest(replacingRelease.getPkg(), new HashMap<>());
		Manifest manifest = new Manifest();
		manifest.setData(manifestData);
		replacingRelease.setManifest(manifest);
		return new ReleaseAnalysisReport(applicationNamesToUpgrade, releaseDifference, existingRelease,
				replacingRelease);
	}

	public Release status(Release release) {
		logger.info("Checking application status for the release: "+ release.getName());
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

	public Release delete(Release release) {
		ApplicationManifest applicationManifest = getApplicationManifest(release);
		String applicationName = applicationManifest.getName();
		DeleteApplicationRequest deleteApplicationRequest = DeleteApplicationRequest.builder().name(applicationName)
				.build();
		this.platformCloudFoundryOperations.getCloudFoundryOperations(release.getPlatformName()).applications()
				.delete(deleteApplicationRequest)
				.doOnSuccess(v -> logger.info("Successfully undeployed app {}", applicationName))
				.doOnError(e -> logger.error("Failed to undeploy app %s", applicationName))
				.block();
		Status deletedStatus = new Status();
		deletedStatus.setStatusCode(StatusCode.DELETED);
		release.getInfo().setStatus(deletedStatus);
		release.getInfo().setDescription("Delete complete");
		this.releaseRepository.save(release);
		return release;
	}

	private void assertApplicationExists(String deploymentId, AppStatus status) {
		DeploymentState state = status.getState();
		if (state == DeploymentState.unknown) {
			throw new IllegalStateException(String.format("App %s is not in a deployed state", deploymentId));
		}
	}

}
