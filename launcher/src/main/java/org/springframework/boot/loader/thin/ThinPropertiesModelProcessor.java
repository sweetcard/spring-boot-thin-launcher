/*
 * Copyright 2016-2017 the original author or authors.
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
package org.springframework.boot.loader.thin;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.model.building.DefaultModelProcessor;
import org.eclipse.aether.artifact.DefaultArtifact;

import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * @author Dave Syer
 *
 */
class ThinPropertiesModelProcessor extends DefaultModelProcessor {

	/**
	 * The default extension for the artifact.
	 */
	final static String DEFAULT_EXTENSION = "jar";

	/**
	 * String representing an empty classifier.
	 */
	final static String EMPTY_CLASSIFIER = "";

	@Override
	public Model read(File input, Map<String, ?> options) throws IOException {
		Model model = super.read(input, options);
		return process(model);
	}

	@Override
	public Model read(Reader input, Map<String, ?> options) throws IOException {
		Model model = super.read(input, options);
		return process(model);
	}

	@Override
	public Model read(InputStream input, Map<String, ?> options) throws IOException {
		try {
			Model model = super.read(new BufferedInputStream(input) {
				@Override
				public void close() throws IOException {
				}
			}, options);
			return process(model);
		}
		finally {
			input.close();
		}
	}

	private Model process(Model model) {
		Properties properties = DependencyResolver.getGlobals();
		if (properties != null) {
			for (String name : properties.stringPropertyNames()) {
				if (name.startsWith("boms.")) {
					String bom = properties.getProperty(name);
					DefaultArtifact artifact = artifact(bom);
					if (model.getDependencyManagement() == null) {
						model.setDependencyManagement(new DependencyManagement());
					}
					boolean replaced = false;
					for (Dependency dependency : model.getDependencyManagement()
							.getDependencies()) {
						if (ObjectUtils.nullSafeEquals(artifact.getArtifactId(),
								dependency.getArtifactId())
								&& ObjectUtils.nullSafeEquals(artifact.getGroupId(),
										dependency.getGroupId())) {
							dependency.setVersion(artifact.getVersion());
						}
					}
					if (isParentBom(model, artifact)) {
						model.getParent().setVersion(artifact.getVersion());
						replaced = true;
					}
					if (!replaced) {
						model.getDependencyManagement().addDependency(bom(artifact));
					}
				}
				else if (name.startsWith("dependencies.")) {
					String pom = properties.getProperty(name);
					DefaultArtifact artifact = artifact(pom);
					boolean replaced = false;
					for (Dependency dependency : model.getDependencies()) {
						if (ObjectUtils.nullSafeEquals(artifact.getArtifactId(),
								dependency.getArtifactId())
								&& ObjectUtils.nullSafeEquals(artifact.getGroupId(),
										dependency.getGroupId())
								&& artifact.getVersion() != null) {
							dependency.setVersion(
									StringUtils.hasLength(artifact.getVersion())
											? artifact.getVersion() : null);
							replaced = true;
						}
					}
					if (!replaced) {
						model.getDependencies().add(dependency(artifact));
					}
				}
				else if (name.startsWith("exclusions.")) {
					String pom = properties.getProperty(name);
					Exclusion exclusion = exclusion(pom);
					for (Dependency dependency : model.getDependencies()) {
						dependency.addExclusion(exclusion);
					}
				}
			}
			addRepositoryIfMissing(model, "central", "https://repo1.maven.org/maven2",
					true, false);
			addRepositoryIfMissing(model, "spring-snapshots",
					"https://repo.spring.io/snapshot", true, true);
			if (properties.containsKey(ThinJarLauncher.THIN_ROOT)) {
				addRepositoryIfMissing(model, "default",
						"file:///${user.home}/repository", true, true);
			}
		}
		return model;
	}

	private void addRepositoryIfMissing(Model model, String id, String url,
			boolean releases, boolean snapshots) {
		for (Repository repo : model.getRepositories()) {
			if (url.equals(repo.getUrl())) {
				return;
			}
			if (id.equals(repo.getId())) {
				return;
			}
		}
		Repository repository = new Repository();
		repository.setLayout("default");
		repository.setId(id);
		repository.setUrl(url);
		RepositoryPolicy enabled = new RepositoryPolicy();
		enabled.setEnabled(true);
		RepositoryPolicy disabled = new RepositoryPolicy();
		disabled.setEnabled(false);
		repository.setReleases(releases ? enabled : disabled);
		repository.setSnapshots(snapshots ? enabled : disabled);
		model.addRepository(repository);
	}

	private Exclusion exclusion(String pom) {
		Exclusion exclusion = new Exclusion();
		DefaultArtifact artifact = artifact(pom);
		exclusion.setGroupId(artifact.getGroupId());
		exclusion.setArtifactId(artifact.getArtifactId());
		return exclusion;
	}

	private Dependency bom(DefaultArtifact artifact) {
		Dependency result = dependency(artifact);
		result.setType("pom");
		result.setScope("import");
		return result;
	}

	private boolean isParentBom(Model model, DefaultArtifact artifact) {
		if (model.getParent() == null) {
			return false;
		}
		if (ObjectUtils.nullSafeEquals(artifact.getArtifactId(),
				model.getParent().getArtifactId())
				&& ObjectUtils.nullSafeEquals(artifact.getGroupId(),
						model.getParent().getGroupId())) {
			return true;
		}
		if (ObjectUtils.nullSafeEquals("spring-boot-starter-parent",
				model.getParent().getArtifactId())
				&& ObjectUtils.nullSafeEquals(artifact.getArtifactId(),
						"spring-boot-dependencies")) {
			return true;
		}
		return false;
	}

	private Dependency dependency(DefaultArtifact artifact) {
		Dependency dependency = new Dependency();
		dependency.setGroupId(artifact.getGroupId());
		dependency.setArtifactId(artifact.getArtifactId());
		dependency.setVersion(StringUtils.hasLength(artifact.getVersion())
				? artifact.getVersion() : null);
		dependency.setClassifier(StringUtils.hasLength(artifact.getClassifier())
				? artifact.getClassifier() : null);
		dependency.setType(artifact.getExtension());
		return dependency;
	}

	private DefaultArtifact artifact(String coordinates) {
		Pattern p = Pattern
				.compile("([^: ]+):([^: ]+)(:([^: ]*)(:([^: ]+))?)?(:([^: ]+))?");
		Matcher m = p.matcher(coordinates);
		Assert.isTrue(m.matches(), "Bad artifact coordinates " + coordinates
				+ ", expected format is <groupId>:<artifactId>[:<extension>[:<classifier>]][:<version>]");
		String groupId = m.group(1);
		String artifactId = m.group(2);
		String version;
		String extension = DEFAULT_EXTENSION;
		String classifier = EMPTY_CLASSIFIER;
		if (StringUtils.hasLength(m.group(6))) {
			if (StringUtils.hasLength(m.group(4))) {
				extension = m.group(4);
			}
			classifier = m.group(6);
			version = StringUtils.hasLength(m.group(8)) ? m.group(8) : null;
		}
		else {
			version = StringUtils.hasLength(m.group(4)) ? m.group(4) : null;
		}
		return new DefaultArtifact(groupId, artifactId, classifier, extension, version);
	}

}
