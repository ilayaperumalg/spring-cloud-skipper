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

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import io.jsonwebtoken.lang.Assert;
import org.cloudfoundry.AbstractCloudFoundryException;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.deployer.resource.support.DelegatingResourceLoader;
import org.springframework.cloud.skipper.deployer.cloudfoundry.PlatformCloudFoundryOperations;
import org.springframework.cloud.skipper.domain.CFApplicationManifestReader;
import org.springframework.cloud.skipper.domain.CFApplicationSkipperManifest;
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

	private final CFApplicationDeployer cfApplicationDeployer;

	public CFManifestDeployerReleaseManager(ReleaseRepository releaseRepository,
			AppDeployerDataRepository appDeployerDataRepository,
			DeployerRepository deployerRepository,
			ReleaseAnalyzer releaseAnalyzer,
			CFApplicationManifestReader cfApplicationManifestReader,
			DelegatingResourceLoader delegatingResourceLoader,
			PlatformCloudFoundryOperations platformCloudFoundryOperations,
			CFApplicationDeployer cfApplicationDeployer) {
		this.releaseRepository = releaseRepository;
		this.appDeployerDataRepository = appDeployerDataRepository;
		this.deployerRepository = deployerRepository;
		this.releaseAnalyzer = releaseAnalyzer;
		this.cfApplicationManifestReader = cfApplicationManifestReader;
		this.delegatingResourceLoader = delegatingResourceLoader;
		this.platformCloudFoundryOperations = platformCloudFoundryOperations;
		this.cfApplicationDeployer = cfApplicationDeployer;
	}

	public SkipperManifestReader getManifestReader() {
		return this.cfApplicationManifestReader;
	}

	public String[] getSupportedManifestKinds() {
		return new String[] { SkipperManifestKind.CFApplication.name() };
	}

	public Release install(Release releaseInput) {
		Release release = this.releaseRepository.save(releaseInput);
		ApplicationManifest applicationManifest = this.cfApplicationDeployer.getCFApplicationManifest(release);
		Assert.isTrue(applicationManifest != null, "CF Application Manifest must be set");
		logger.debug("Manifest = " + ArgumentSanitizer.sanitizeYml(releaseInput.getManifest().getData()));
		// Deploy the application
		String applicationName = applicationManifest.getName();
		Map<String, String> appDeploymentData = new HashMap<>();
		appDeploymentData.put(applicationManifest.getName(), applicationManifest.toString());
		this.platformCloudFoundryOperations.getCloudFoundryOperations(releaseInput.getPlatformName())
				.applications().pushManifest(
				PushApplicationManifestRequest.builder()
						.manifest(applicationManifest)
						.stagingTimeout(Duration.ofMinutes(15L))
						.startupTimeout(Duration.ofMinutes(5L))
						.build())
				.doOnSuccess(v -> logger.info("Done uploading bits for {}", applicationName))
				.doOnError(e -> logger.error(
						String.format("Error creating app %s.  Exception Message %s", applicationName,
								e.getMessage())))
				.timeout(Duration.ofSeconds(360L))
				.doOnTerminate((item, error) -> {
					if (error == null) {
						logger.info("Successfully deployed {}", applicationName);
						AppDeployerData appDeployerData = new AppDeployerData();
						appDeployerData.setReleaseName(release.getName());
						appDeployerData.setReleaseVersion(release.getVersion());
						appDeployerData.setDeploymentDataUsingMap(appDeploymentData);

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
						logger.error(String.format("Failed to deploy %s", applicationName));
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

	public List<CFApplicationSkipperManifest> getReleaseManifest(Release release) {
		return this.cfApplicationManifestReader.read(release.getManifest().getData());
	}

	@Override
	public ReleaseAnalysisReport createReport(Release existingRelease, Release replacingRelease) {
		List<CFApplicationSkipperManifest> existingReleaseManifest = getReleaseManifest(existingRelease);
		List<CFApplicationSkipperManifest> replacingReleaseManifest = getReleaseManifest(replacingRelease);
		//		ApplicationManifest existingApplicationManifest = updateApplicationPath(existingRelease);
		//		ApplicationManifest replacingApplicationManifest = updateApplicationPath(replacingRelease);
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
		release.getInfo().getStatus().setPlatformStatusAsAppStatusList(
				Collections.singletonList(this.cfApplicationDeployer.status(release)));
		return release;
	}

	public Release delete(Release release) {
		this.releaseRepository.save(this.cfApplicationDeployer.delete(release));
		return release;
	}

}
