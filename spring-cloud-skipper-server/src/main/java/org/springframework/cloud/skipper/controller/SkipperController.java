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
package org.springframework.cloud.skipper.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.skipper.domain.AboutInfo;
import org.springframework.cloud.skipper.domain.InstallProperties;
import org.springframework.cloud.skipper.domain.InstallRequest;
import org.springframework.cloud.skipper.domain.PackageMetadata;
import org.springframework.cloud.skipper.domain.Release;
import org.springframework.cloud.skipper.domain.UpgradeRequest;
import org.springframework.cloud.skipper.domain.UploadRequest;
import org.springframework.cloud.skipper.service.PackageService;
import org.springframework.cloud.skipper.service.ReleaseService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Skipper server related operations such as install, upgrade, delete, and rollback.
 *
 * @author Mark Pollack
 * @author Ilayaperumal Gopinathan
 */
@RestController
@RequestMapping("/")
public class SkipperController {

	private final ReleaseService releaseService;

	private final PackageService packageService;

	@Autowired
	public SkipperController(ReleaseService releaseService, PackageService packageService) {
		this.releaseService = releaseService;
		this.packageService = packageService;
	}

	@RequestMapping(path = "/about", method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public AboutInfo getAboutInfo() {
		return new AboutInfo("1.0.0");
	}

	@RequestMapping(path = "/upload", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.CREATED)
	public PackageMetadata upload(@RequestBody UploadRequest uploadRequest) {
		return this.packageService.upload(uploadRequest);
	}

	@RequestMapping(path = "/install", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.CREATED)
	public Release install(@RequestBody InstallRequest installRequest) {
		return this.releaseService.install(installRequest);
	}

	@RequestMapping(path = "/install/{id}", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.CREATED)
	public Release deploy(@PathVariable("id") String id, @RequestBody InstallProperties installProperties) {
		return this.releaseService.install(id, installProperties);
	}

	@RequestMapping(path = "/status/{name}/{version}", method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public Release status(@PathVariable("name") String name, @PathVariable("version") int version) {
		return this.releaseService.status(name, version);
	}

	@RequestMapping(path = "/upgrade", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.CREATED)
	public Release upgrade(@RequestBody UpgradeRequest upgradeRequest) {
		return this.releaseService.upgrade(upgradeRequest);
	}

	@RequestMapping(path = "/rollback/{name}/{version}", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.CREATED)
	public Release rollback(@PathVariable("name") String releaseName,
			@PathVariable("version") int rollbackVersion) {
		return this.releaseService.rollback(releaseName, rollbackVersion);
	}

	@RequestMapping(path = "/delete/{name}", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.CREATED)
	public Release delete(@PathVariable("name") String releaseName) {
		return this.releaseService.delete(releaseName);
	}
}
