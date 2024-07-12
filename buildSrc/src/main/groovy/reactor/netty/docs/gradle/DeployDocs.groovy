/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.docs.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Zip;

/**
 * The purpose of this plugin is to prepare and upload javadoc + reference docs to docs.spring.io server.
 */
class DeployDocs implements Plugin<Project> {
	// Define constants for property keys
	static final String SSH_HOST_KEY = 'deployDocsSshHostKey'
	static final String SSH_HOST = 'deployDocsHost'
	static final String SSH_USERNAME = 'deployDocsSshUsername'
	static final String SSH_KEY = 'deployDocsSshKey'

	@Override
	public void apply(Project project) {
		// We are using this ssh plugin (take care: requires a JDK11 compatible version)
		project.getPluginManager().apply('org.hidetake.ssh')

		// configure task that creates a temporary zip (with both javadoc + reference doc) that will be uploaded to remote docs host
		configureDeployDocsZipTask(project)

		// configure task that upload the zip and install it on the remote docs host using ssh commands
		configureDeployDocsTask(project)
	}

	private void configureDeployDocsZipTask(Project project) {
		project.tasks.register('deployDocsZip', Zip) {
			def buildDir = new File("${project.rootProject.projectDir}/reactor-netty/build")
			if (!buildDir.exists()) {
				logger.debug('Adding dependency on asciidoctor, asciidoctorPdf, javadoc')
				dependsOn(':reactor-netty:asciidoctor', ':reactor-netty:asciidoctorPdf', ':reactor-netty:javadoc')
			}

			archiveBaseName.set("reactor-netty")
			archiveClassifier.set("apidocs")

			from("${project.rootProject.projectDir}/reactor-netty/build/asciidoc/pdf") {
				include 'index.pdf'  // Include only the index.pdf file
				into("reference/pdf/")
				includeEmptyDirs = false
				eachFile { fileCopyDetails ->
					if (fileCopyDetails.name == 'index.pdf') {
						fileCopyDetails.name = "reactor-netty-reference-guide-${project.rootProject.version}.pdf"
					}
				}
			}
			from("${project.rootProject.projectDir}/reactor-netty/build/asciidoc/") {
				includeEmptyDirs = false
				exclude "**/index.pdf"
				into("reference/html/")
			}
			from("${project.rootProject.projectDir}/reactor-netty/build/docs/javadoc/") {
				into "api"
			}
		}
	}

	private void configureDeployDocsTask(Project project) {
		project.task('deployDocs') {
			dependsOn 'deployDocsZip'
			doLast {
				configureSsh(project)

				project.ssh.run {
					session(project.remotes.docs) {
						def now = System.currentTimeMillis()
						def name = project.rootProject.name
						def version = project.rootProject.version
						def tempPath = "/tmp/${name}-${now}-docs/".replaceAll(' ', '_')
						execute "mkdir -p $tempPath"

						project.tasks.deployDocsZip.outputs.each { o ->
							put from: o.files, into: tempPath
						}

						execute "unzip $tempPath*.zip -d $tempPath"

						def extractPath = "/opt/www/domains/spring.io/docs/htdocs/projectreactor/${name}/docs/${version}/"

						execute "rm -rf $extractPath"
						execute "mkdir -p $extractPath"
						execute "mv $tempPath* $extractPath"
						execute "chmod -R g+w $extractPath"
						execute "rm -rf $tempPath"
						execute "rm -f $extractPath*.zip"

						// update sym links with autoln command
						execute "/opt/www/domains/spring.io/docs/autoln/bin/autoln create --scan-dir=/opt/www/domains/spring.io/docs/htdocs/projectreactor/${name}/docs/ --maxdepth=2"
					}
				}
			}
		}
	}

	private void configureSsh(Project project) {
		project.remotes {
			docs {
				validateProperties(project)

				// write content of known hosts into a temp file
				def knownHostsFile = File.createTempFile("known_hosts_", ".txt")
				knownHostsFile.text = project.findProperty(SSH_HOST_KEY)
				knownHostsFile.deleteOnExit()

				// configure ssh
				role 'docs'
				knownHosts = knownHostsFile
				host = project.findProperty(SSH_HOST)
				user = project.findProperty(SSH_USERNAME)
				identity = identity = project.findProperty(SSH_KEY)
				retryCount = 5 // retry 5 times (default is 0)
				retryWaitSec = 3 // wait 10 seconds between retries (default is 0)
			}
		}
	}

	private void validateProperties(Project project) {
		def requiredProperties = [SSH_HOST_KEY, SSH_HOST, SSH_USERNAME, SSH_KEY]
		requiredProperties.each { prop ->
			if (!project.hasProperty(prop)) {
				throw new GradleException("deployDocs task failed: ${prop} property undefined")
			}
		}
		if (!project.name) {
			throw new GradleException("deployDocs task failed: property name undefined")
		}
		if (!project.version) {
			throw new GradleException("deployDocs task failed: property version undefined")
		}
	}
}