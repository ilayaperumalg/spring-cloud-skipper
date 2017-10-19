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

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.skipper.deployer.AppDeployerReleaseManager;
import org.springframework.cloud.skipper.domain.Info;
import org.springframework.cloud.skipper.domain.StatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * @author Mark Pollack
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractMockMvcTests {

	private final Logger logger = LoggerFactory.getLogger(AbstractMockMvcTests.class);

	private final MediaType contentType = new MediaType(MediaType.APPLICATION_JSON.getType(),
			MediaType.APPLICATION_JSON.getSubtype(), Charset.forName("utf8"));

	protected MockMvc mockMvc;

	@Autowired
	protected WebApplicationContext wac;

	public static String convertObjectToJson(Object object) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
		String json = mapper.writeValueAsString(object);
		return json;
	}

	@Before
	public void setupMockMvc() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(wac)
				.defaultRequest(get("/").accept(MediaType.APPLICATION_JSON).contentType(contentType))
				.build();
	}

	protected void assertReleaseIsDeployedSuccessfully(String releaseName, String releaseVersion)
			throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(1);
		long startTime = System.currentTimeMillis();
		while (!isDeployed(releaseName, releaseVersion)) {
			if ((System.currentTimeMillis() - startTime) > 60000) {
				logger.info("Stopping polling for deployed status after 60 seconds for release={} version={}",
						releaseName, releaseVersion);
				break;
			}
			Thread.sleep(10000);
		}
		if (isDeployed(releaseName, releaseVersion)) {
			latch.countDown();
		}
		assertThat(latch.await(1, TimeUnit.SECONDS)).describedAs("Status check timed out").isTrue();
	}

	private boolean isDeployed(String releaseName, String releaseVersion) {
		try {
			logger.info("Checking status of release={} version={}", releaseName, releaseVersion);
			MvcResult result = mockMvc.perform(get(String.format("/status/%s/%s", releaseName, releaseVersion)))
					.andReturn();
			Info info = convertContentToInfo(result.getResponse().getContentAsString());

			logger.info("Status = " + info.getStatus());
			return info.getStatus().getStatusCode().equals(StatusCode.DEPLOYED) &&
					info.getStatus().getPlatformStatus().contains(AppDeployerReleaseManager.ALL_APPS_DEPLOYED_MESSAGE);
		}
		catch (Exception e) {
			return false;
		}
	}

	private Info convertContentToInfo(String json) {
		ObjectMapper objectMapper = new ObjectMapper();
		try {
			return objectMapper.readValue(json, new TypeReference<Info>() {
			});
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Can't parse JSON for Info", e);
		}
	}

}
