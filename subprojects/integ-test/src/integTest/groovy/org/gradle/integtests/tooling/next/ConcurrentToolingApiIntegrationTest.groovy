/*
 * Copyright 2011 the original author or authors.
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

package org.gradle.integtests.tooling.next

import org.gradle.integtests.fixtures.BasicGradleDistribution
import org.gradle.integtests.tooling.fixture.MinTargetGradleVersion
import org.gradle.integtests.tooling.fixture.MinToolingApiVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.logging.ProgressLoggerFactory
import org.gradle.tests.fixtures.ConcurrentTestUtil
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProgressEvent
import org.gradle.tooling.ProgressListener
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.internal.consumer.ConnectorServices
import org.gradle.tooling.internal.consumer.Distribution
import org.gradle.tooling.model.Project
import org.gradle.tooling.model.idea.IdeaProject
import org.junit.Rule
import spock.lang.Ignore
import spock.lang.Issue

@MinToolingApiVersion(currentOnly = true)
@MinTargetGradleVersion(currentOnly = true)
@Issue("GRADLE-1933")
class ConcurrentToolingApiIntegrationTest extends ToolingApiSpecification {

    @Rule def concurrent = new ConcurrentTestUtil()
    int threads = 3

    def setup() {
        toolingApi.isEmbedded = false
        concurrent.shortTimeout = 20000
        new ConnectorServices().reset()
    }

    def "handles concurrent scenario"() {
        dist.file('build.gradle')  << "apply plugin: 'java'"

        when:
        threads.times {
            concurrent.start { useToolingApi() }
        }

        then:
        concurrent.finished()
    }

    def "handles standard input concurrently when getting model"() {
        when:
        threads.times { idx ->
            dist.file("build$idx/build.gradle") << "description = System.in.text"
        }

        then:
        threads.times { idx ->
            concurrent.start {
                withConnectionInDir("build$idx") { connection ->
                    def model = connection.model(Project.class)
                    model.standardInput = new ByteArrayInputStream("project $idx".toString().bytes)
                    def project = model.get()
                    assert project.description == "project $idx"
                }
            }
        }

        concurrent.finished()
    }

    def "handles standard input concurrently when running build"() {
        when:
        threads.times { idx ->
            dist.file("build$idx/build.gradle") << "task show << { println System.in.text}"
        }

        then:
        threads.times { idx ->
            concurrent.start {
                withConnectionInDir("build$idx") { connection ->
                    def out = new ByteArrayOutputStream()
                    def build = connection.newBuild()
                    build.standardInput = new ByteArrayInputStream("hasta la vista $idx".toString().bytes)
                    build.forTasks('show')
                    build.standardOutput = out
                    build.run()
                    assert out.toString().contains("hasta la vista $idx")
                }
            }
        }

        concurrent.finished()
    }

    def "receives distribution progress concurrently"() {
        given:
        threads.times { idx ->
            dist.file("build$idx/build.gradle") << "apply plugin: 'java'"
        }

        when:
        threads.times { idx ->
            concurrent.start {
                def connector = toolingApi.connector()
                connector.distribution = new ProgressLoggingDistro(message: "download for $idx", delegate: connector.distribution)
                def connection = connector.connect()

                try {
                    def model = connection.model(Project)
                    def listener = new ProgressTrackingListener()
                    model.addProgressListener(listener)
                    assert model.get()
                    assert listener.progressMessages.contains("download for " + idx)
                    assert !listener.progressMessages.contains("download for " + ++idx)
                } finally {
                    connection.close()
                }
            }
        }

        then:
        concurrent.finished()
    }

    static class ProgressLoggingDistro implements Distribution {

        Distribution delegate
        String message

        String getDisplayName() {
            return 'mock'
        }

        Set<File> getToolingImplementationClasspath(ProgressLoggerFactory progressLoggerFactory) {
            def o = progressLoggerFactory.newOperation("mock")
            o.description = message
            o.started()
            o.completed()
            return delegate.getToolingImplementationClasspath(progressLoggerFactory)
        }

    }

    static class ProgressTrackingListener implements ProgressListener {
        def progressMessages = []
        void statusChanged(ProgressEvent event) {
            progressMessages << event.description
        }
    }

    def "can share connection when running build"() {
        given:
        dist.file("build.gradle") << """
def text = System.in.text
System.out.println 'out=' + text
System.err.println 'err=' + text
project.description = text
"""

        when:
        withConnection { connection ->
            threads.times { idx ->
                concurrent.start {
                    def stdout = new ByteArrayOutputStream()
                    def stderr = new ByteArrayOutputStream()
                    def listener = new ProgressTrackingListener()

                    def model = connection.model(Project.class)
                    model.standardInput = new ByteArrayInputStream("hasta la vista $idx".toString().bytes)
                    model.standardOutput = stdout
                    model.standardError = stderr
                    model.addProgressListener(listener)
                    assert model.get().description == "hasta la vista $idx"

                    assert stdout.toString().contains("out=hasta la vista $idx")
                    assert stdout.toString().count("out=hasta la vista") == 1

                    assert stderr.toString().contains("err=hasta la vista $idx")
                    assert stderr.toString().count("err=hasta la vista") == 1
                }
            }
            concurrent.finished()
        }

        then: noExceptionThrown()
    }

    @Ignore
    //TODO SF enable this test after releasing 1.7
    def "handles concurrent builds with different target Gradle version"() {
        dist.file('build.gradle')  << "apply plugin: 'java'"

        when:
        threads.times { concurrent.start { useToolingApi() } }
        threads.times { concurrent.start { useToolingApi(dist.previousVersion("1.0-milestone-7"))} }

        then:
        concurrent.finished()
    }

    def useToolingApi(BasicGradleDistribution target = null) {
        if (target != null) {
            selectTargetDist(target)
        }

        withConnection { ProjectConnection connection ->
            try {
                def model = connection.getModel(IdeaProject)
                assert model != null
                //a bit more stress:
                connection.newBuild().forTasks('tasks').run()
            } catch (Exception e) {
                throw new RuntimeException("""We might have hit a concurrency problem.
See the full stacktrace and the list of causes to investigate""", e);
            }
        }
    }

    //TODO SF DSLize this and the other test
    def "receives progress and logging while the model is building"() {
        when:
        //create build folders with slightly different builds
        threads.times { idx ->
            dist.file("build$idx/build.gradle") << """
System.out.println 'this is stdout: $idx'
System.err.println 'this is stderr: $idx'
"""
        }

        then:
        threads.times { idx ->
            concurrent.start {
                withConnectionInDir("build$idx") { connection ->
                    def stdout = new ByteArrayOutputStream()
                    def stderr = new ByteArrayOutputStream()
                    def listener = new ProgressTrackingListener()

                    def model = connection.model(Project.class)
                    model.standardOutput = stdout
                    model.standardError = stderr
                    model.addProgressListener(listener)
                    assert model.get()

                    assert stdout.toString().contains("this is stdout: $idx")
                    assert stdout.toString().count("this is stdout") == 1

                    assert stderr.toString().contains("this is stderr: $idx")
                    assert stderr.toString().count("this is stderr") == 1
                }
            }
        }

        concurrent.finished()
    }

    def "receives progress and logging while the build is executing"() {
        when:
        //create build folders with slightly different builds
        threads.times { idx ->
            dist.file("build$idx/build.gradle") << """
System.out.println 'this is stdout: $idx'
System.err.println 'this is stderr: $idx'
"""
        }

        then:
        threads.times { idx ->
            concurrent.start {
                withConnectionInDir("build$idx") { connection ->
                    def stdout = new ByteArrayOutputStream()
                    def stderr = new ByteArrayOutputStream()
                    def listener = new ProgressTrackingListener()

                    def build = connection.newBuild()
                    build.standardOutput = stdout
                    build.standardError = stderr
                    build.addProgressListener(listener)
                    build.run()

                    assert stdout.toString().contains("this is stdout: $idx")
                    assert stdout.toString().count("this is stdout") == 1

                    assert stderr.toString().contains("this is stderr: $idx")
                    assert stderr.toString().count("this is stderr") == 1
                }
            }
        }

        concurrent.finished()
    }

    def withConnectionInDir(String dir, Closure cl) {
        GradleConnector connector = toolingApi.connector()
        connector.forProjectDirectory(dist.file(dir))
        ProjectConnection connection = connector.connect()
        try {
            return cl(connection)
        } finally {
            connection.close();
        }
    }
}
