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
package org.springframework.cloud.skipper;

import java.io.File;

import org.springframework.cloud.skipper.domain.PackageMetadata;

/**
 * Utility methods used by Skipper.
 *
 * @author Ilayaperumal Gopinathan
 */
public class SkipperUtils {

	public static File calculatePackageZipFile(PackageMetadata packageMetadata, File targetPath) {
		return new File(targetPath, packageMetadata.getName() + "-" + packageMetadata.getVersion() + ".zip");
	}

	public static int getNumberedVersion(String version) {
		return Integer.valueOf(version.replaceAll("\\.", ""));
	}

}
