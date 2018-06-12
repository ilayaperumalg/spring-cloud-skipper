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

import java.util.ArrayList;
import java.util.List;

import org.springframework.cloud.skipper.SkipperException;
import org.springframework.cloud.skipper.domain.Release;

/**
 * @author Ilayaperumal Gopinathan
 */
public class DelegatingReleaseManager implements ReleaseManager {

	private List<ReleaseManager> releaseManagers = new ArrayList<>();

	public DelegatingReleaseManager(List<ReleaseManager> releaseManagers) {
		this.releaseManagers = releaseManagers;
	}

	@Override
	public Release install(Release release) {
		for (ReleaseManager releaseManager: this.releaseManagers) {
			if (releaseManager.canSupport(release)) {
				return releaseManager.install(release);
			}
		}
		throw new SkipperException("ReleaseManager not found for the release. " + release.getName());
	}

	@Override
	public ReleaseAnalysisReport createReport(Release existingRelease, Release replacingRelease,
			boolean initial) {
		for (ReleaseManager releaseManager: this.releaseManagers) {
			if (releaseManager.canSupport(replacingRelease)) {
				return releaseManager.createReport(existingRelease, replacingRelease, initial);
			}
		}
		throw new SkipperException("ReleaseManager not found for the release. " + replacingRelease.getName());
	}

	@Override
	public Release delete(Release release) {
		for (ReleaseManager releaseManager: this.releaseManagers) {
			if (releaseManager.canSupport(release)) {
				return releaseManager.delete(release);
			}
		}
		throw new SkipperException("ReleaseManager not found for the release. " + release.getName());
	}

	@Override
	public Release status(Release release) {
		for (ReleaseManager releaseManager: this.releaseManagers) {
			if (releaseManager.canSupport(release)) {
				return releaseManager.status(release);
			}
		}
		throw new SkipperException("ReleaseManager not found for the release. " + release.getName());
	}

	@Override
	public boolean canSupport(Release release) {
		return false;
	}
}
