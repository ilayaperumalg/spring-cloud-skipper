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

import org.springframework.cloud.skipper.domain.CFApplicationManifestReader;
import org.springframework.cloud.skipper.domain.Release;
import org.springframework.cloud.skipper.domain.SkipperManifestReader;
import org.springframework.cloud.skipper.domain.SpringCloudDeployerApplicationManifestReader;

public class CompositeReleaseManager implements ReleaseManager {

	private final AppDeployerReleaseManager appDeployerReleaseManager;

	private final SpringCloudDeployerApplicationManifestReader applicationManifestReader;

	private final CFManifestDeployerReleaseManager cfManifestDeployerReleaseManager;

	private final CFApplicationManifestReader cfApplicationManifestReader;

	public CompositeReleaseManager(AppDeployerReleaseManager appDeployerReleaseManager, CFManifestDeployerReleaseManager cfManifestDeployerReleaseManager,
			SpringCloudDeployerApplicationManifestReader applicationManifestReader,
			CFApplicationManifestReader cfApplicationManifestReader) {
		this.appDeployerReleaseManager = appDeployerReleaseManager;
		this.cfManifestDeployerReleaseManager = cfManifestDeployerReleaseManager;
		this.applicationManifestReader = applicationManifestReader;
		this.cfApplicationManifestReader = cfApplicationManifestReader;
	}

	@Override
	public Release install(Release release) {
		return getReleaseManager(release).install(release);
	}

	private ReleaseManager getReleaseManager(Release release) {
		String releaseManifest = release.getManifest().getData();
		if (this.applicationManifestReader.assertSupportedKinds(releaseManifest)) {
			return this.appDeployerReleaseManager;
		}
		else if (this.cfApplicationManifestReader.assertSupportedKinds(releaseManifest)) {
			return this.cfManifestDeployerReleaseManager;
		}
		throw new IllegalStateException("Could not find appropriate Release Manager");
	}

	@Override
	public ReleaseAnalysisReport createReport(Release existingRelease, Release replacingRelease) {
		return getReleaseManager(existingRelease).createReport(existingRelease, replacingRelease);
	}

	@Override
	public Release delete(Release release) {
		return getReleaseManager(release).delete(release);
	}

	@Override
	public Release status(Release release) {
		return getReleaseManager(release).status(release);
	}

	@Override
	public SkipperManifestReader getManifestReader() {
		return null;
	}

	@Override
	public String[] getSupportedManifestKinds() {
		return null;
	}
}
