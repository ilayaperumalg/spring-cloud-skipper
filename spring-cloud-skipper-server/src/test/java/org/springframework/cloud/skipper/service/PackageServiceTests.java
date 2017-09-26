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
package org.springframework.cloud.skipper.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.skipper.AbstractIntegrationTest;
import org.springframework.cloud.skipper.domain.ConfigValues;
import org.springframework.cloud.skipper.domain.Package;
import org.springframework.cloud.skipper.domain.PackageMetadata;
import org.springframework.cloud.skipper.domain.Repository;
import org.springframework.cloud.skipper.domain.Template;
import org.springframework.cloud.skipper.domain.UploadRequest;
import org.springframework.cloud.skipper.index.PackageException;
import org.springframework.cloud.skipper.repository.PackageMetadataRepository;
import org.springframework.cloud.skipper.repository.RepositoryRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * @author Mark Pollack
 * @author Ilayaperumal Gopinathan
 */
@ActiveProfiles("repo-test")
public class PackageServiceTests extends AbstractIntegrationTest {

	private final Logger logger = LoggerFactory.getLogger(PackageServiceTests.class);

	@Autowired
	private PackageService packageService;

	@Autowired
	private PackageMetadataRepository packageMetadataRepository;

	@Autowired
	private RepositoryRepository repositoryRepository;

	@Test
	public void testExceptions() {
		PackageMetadata packageMetadata = new PackageMetadata();
		packageMetadata.setName("noname");
		packageMetadata.setVersion("noversion");
		assertThat(packageService).isNotNull();
		assertThatThrownBy(() -> packageService.downloadPackage(packageMetadata))
				.isInstanceOf(PackageException.class)
				.withFailMessage(
						"Resource for Package name 'noname', version 'noversion' was not found in any repository.");
		assertThatThrownBy(() -> packageService.downloadPackage(packageMetadata)).isInstanceOf(PackageException.class);
	}

	@Test
	public void download() {
		PackageMetadata packageMetadata = packageMetadataRepository.findByNameAndVersion("log", "1.0.0");
		// Other tests may have caused the file to be loaded into the database, ensure we start
		// fresh.
		if (packageMetadata.getPackageFileBytes() != null) {
			packageMetadata.setPackageFileBytes(null);
			packageMetadataRepository.save(packageMetadata);
		}
		packageMetadata = packageMetadataRepository.findByNameAndVersion("log", "1.0.0");
		assertThat(packageMetadata).isNotNull();
		assertThat(packageMetadata.getPackageFileBytes()).isNullOrEmpty();
		assertThat(packageService).isNotNull();
		assertThat(packageMetadata.getId()).isNotNull();
		assertThat(packageMetadata.getOrigin()).isNotNull();
		Repository repository = repositoryRepository.findOne(packageMetadata.getOrigin());
		assertThat(repository).isNotNull();

		Package downloadedPackage = packageService.downloadPackage(packageMetadata);
		assertThat(downloadedPackage.getMetadata().getPackageFileBytes()).isNotNull();
		assertThat(downloadedPackage.getMetadata()).isEqualToIgnoringGivenFields(packageMetadata);
		assertThat(downloadedPackage.getTemplates()).isNotNull();
		assertThat(downloadedPackage.getConfigValues()).isNotNull();
		packageMetadata = packageMetadataRepository.findByNameAndVersion("log", "1.0.0");
		assertThat(packageMetadata.getPackageFileBytes()).isNotNull();
	}

	@Test
	public void upload() throws Exception {
		// Create throw away repository, treated to be a 'local' database repo by default for now.
		Repository repository = new Repository();
		repository.setName("database-repo");
		repository.setUrl("http://example.com/repository/");
		this.repositoryRepository.save(repository);

		// Package log 9.9.9 should not exist, since it hasn't been uploaded yet.
		PackageMetadata packageMetadata = packageMetadataRepository.findByNameAndVersion("log", "9.9.9");
		assertThat(packageMetadata).isNull();

		UploadRequest uploadProperties = new UploadRequest();
		uploadProperties.setRepoName("database-repo");
		uploadProperties.setName("log");
		uploadProperties.setVersion("9.9.9");
		uploadProperties.setExtension("zip");
		Resource resource = new ClassPathResource("/org/springframework/cloud/skipper/service/log-9.9.9.zip");
		assertThat(resource.exists()).isTrue();
		byte[] originalPackageBytes = StreamUtils.copyToByteArray(resource.getInputStream());
		assertThat(originalPackageBytes).isNotEmpty();
		Assert.isTrue(originalPackageBytes.length != 0,
				"PackageServiceTests.Assert.isTrue: Package file as bytes must not be empty");
		uploadProperties.setPackageFileAsBytes(originalPackageBytes);

		// Upload new package
		assertThat(packageService).isNotNull();
		PackageMetadata uploadedPackageMetadata = this.packageService.upload(uploadProperties);
		assertThat(uploadedPackageMetadata.getName().equals("log")).isTrue();
		assertThat(uploadedPackageMetadata.getVersion().equals("9.9.9")).isTrue();

		// Retrieve new package
		PackageMetadata retrievedPackageMetadata = packageMetadataRepository.findByNameAndVersion("log", "9.9.9");
		assertThat(retrievedPackageMetadata.getName().equals("log")).isTrue();
		assertThat(retrievedPackageMetadata.getVersion().equals("9.9.9")).isTrue();
		assertThat(retrievedPackageMetadata).isNotNull();
		assertThat(retrievedPackageMetadata.getPackageFileBytes()).isNotNull();
		byte[] retrievedPackageBytes = retrievedPackageMetadata.getPackageFileBytes();
		assertThat(originalPackageBytes).isEqualTo(retrievedPackageBytes);

		// Check that package can be deserialized from the database.
		Package downloadedPackage = packageService.downloadPackage(retrievedPackageMetadata);
		assertThat(downloadedPackage.getMetadata()).isEqualToIgnoringGivenFields(retrievedPackageMetadata);
		assertThat(downloadedPackage.getTemplates()).isNotNull();
		assertThat(downloadedPackage.getConfigValues()).isNotNull();

	}

	@Test
	public void deserializePackage() {
		PackageMetadata packageMetadata = this.packageMetadataRepository.findByNameAndVersion("log", "1.0.0");
		assertThat(packageService).isNotNull();
		Package pkg = packageService.downloadPackage(packageMetadata);
		assertThat(pkg).isNotNull();
		assertThat(pkg.getConfigValues().getRaw()).contains("1024m");
		assertThat(pkg.getMetadata()).isEqualToIgnoringGivenFields(packageMetadata, "id", "origin", "packageFile");
		assertThat(pkg.getTemplates()).hasSize(1);
		Template template = pkg.getTemplates().get(0);
		assertThat(template.getName()).isEqualTo("log.yml");
		assertThat(template.getData()).isNotEmpty();
	}

	@Test
	public void deserializeNestedPackage() {
		PackageMetadata packageMetadata = this.packageMetadataRepository.findByNameAndVersion("ticktock", "1.0.0");
		assertThat(packageService).isNotNull();
		Package pkg = packageService.downloadPackage(packageMetadata);
		assertThat(pkg).isNotNull();
		assertThat(pkg.getMetadata()).isEqualToIgnoringGivenFields(packageMetadata, "id", "origin", "packageFile");
		assertThat(pkg.getDependencies()).hasSize(2);
		List<String> packageNames = new ArrayList<>();
		packageNames.add(pkg.getDependencies().get(0).getMetadata().getName());
		packageNames.add(pkg.getDependencies().get(1).getMetadata().getName());
		assertThat(packageNames).containsExactlyInAnyOrder("time", "log");
		assertPackageContent(pkg.getDependencies().get(0));
		assertPackageContent(pkg.getDependencies().get(1));
	}

	private void assertPackageContent(Package pkgContent) {
		String packageName = pkgContent.getMetadata().getName();
		assertThat(packageName).isIn("time", "log");
		assertThat(pkgContent).isNotNull();
		assertConfigValues(pkgContent);
		if (packageName.equals("log")) {
			assertThat(pkgContent.getMetadata().getName()).isEqualTo("log");
			assertThat(pkgContent.getMetadata().getVersion()).isEqualTo("2.0.0");
		}
		else {
			assertThat(pkgContent.getMetadata().getName()).isEqualTo("time");
			assertThat(pkgContent.getMetadata().getVersion()).isEqualTo("2.0.0");
		}
	}

	protected void assertConfigValues(Package pkg) {
		ConfigValues configValues = pkg.getConfigValues();
		Yaml yaml = new Yaml();
		Map logConfigValueMap = (Map) yaml.load(configValues.getRaw());
		assertThat(logConfigValueMap).containsKeys("appVersion", "deployment");
		Map deploymentMap = (Map) logConfigValueMap.get("deployment");
		assertThat(deploymentMap).contains(entry("count", 1));
	}
}
