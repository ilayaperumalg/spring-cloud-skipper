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
package org.springframework.cloud.skipper.client;

import org.springframework.cloud.skipper.domain.AboutInfo;
import org.springframework.cloud.skipper.domain.DeployProperties;
import org.springframework.cloud.skipper.domain.DeployRequest;
import org.springframework.cloud.skipper.domain.PackageMetadata;
import org.springframework.cloud.skipper.domain.PackageUploadProperties;
import org.springframework.cloud.skipper.domain.Release;
import org.springframework.cloud.skipper.domain.Repository;
import org.springframework.cloud.skipper.domain.UpdateRequest;
import org.springframework.hateoas.Resources;

/**
 * The main client side interface to communicate with the Skipper Server.
 *
 * @author Mark Pollack
 * @author Ilayaperumal Gopinathan
 */
public interface SkipperClient {

	static SkipperClient create(String baseUrl) {
		return new DefaultSkipperClient(baseUrl);
	}

	/**
	 * @return The AboutInfo for the server
	 */
	AboutInfo getAboutInfo();

	/**
	 * Search for package metadata.
	 * @param name optional name with wildcard support for searching
	 * @param details boolean flag to fetch all the metadata.
	 * @return the package metadata with the projection set to summary
	 */
	Resources<PackageMetadata> getPackageMetadata(String name, boolean details);

	/**
	 * Deploy the package.
	 *
	 * @param packageId the package Id.
	 * @param deployProperties the (@link DeployProperties)
	 * @return the deployed {@link Release}
	 */
	String deploy(String packageId, DeployProperties deployProperties);

	/**
	 * Deploy the package
	 * @param deployRequest the package deploy request
	 * @return the deployed {@link Release}
	 */
	Release deploy(DeployRequest deployRequest);

	/**
	 * Update the package.
	 * @param updateRequest the request to update the release
	 * @return the deploye {@link Release}
	 */
	Release update(UpdateRequest updateRequest);

	/*
	 * Upload the package.
	 *
	 * @param packageUploadProperties the properties for the package upload.
	 * @return package metadata for the uploaded package
	 */
	PackageMetadata upload(PackageUploadProperties packageUploadProperties);

	/**
	 * Undeploy a specific release.
	 *
	 * @param releaseName the release name
	 * @return the un-deployed {@link Release}
	 */
	Release undeploy(String releaseName);

	/**
	 * Rollback a specific release.
	 *
	 * @param releaseName the release name
	 * @param releaseVersion the release version.
	 * @return the rolled back {@link Release}
	 */
	Release rollback(String releaseName, int releaseVersion);

	/**
	 * Add a new Package Repository.
	 *
	 * @param name the name of the repository
	 * @param rootUrl the root URL for the package
	 * @param sourceUrl the source URL for the packages
	 * @return the newly added Repository
	 */
	Repository addRepository(String name, String rootUrl, String sourceUrl);

	/**
	 * Delete a Package Repository.
	 *
	 * @param name the name of the repository
	 */
	void deleteRepository(String name);

	/**
	 * List Package Repositories.
	 *
	 * @return the list of package repositories
	 */
	Resources<Repository> listRepositories();
}
