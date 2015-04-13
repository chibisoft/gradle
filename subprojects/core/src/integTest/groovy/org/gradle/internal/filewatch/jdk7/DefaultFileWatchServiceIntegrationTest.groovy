/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.internal.filewatch.jdk7

import org.gradle.api.internal.file.collections.DirectoryFileTree
import org.gradle.internal.filewatch.DefaultFileWatchInputs
import org.gradle.internal.filewatch.FileWatchInputs
import org.gradle.internal.filewatch.FileWatchListener
import org.gradle.internal.filewatch.FileWatcher
import org.gradle.internal.os.OperatingSystem
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.testfixtures.internal.NativeServicesTestFixture
import org.junit.Rule
import spock.lang.Specification

/**
 * Created by lari on 13/04/15.
 */
class DefaultFileWatchServiceIntegrationTest extends Specification {
    @Rule
    public final TestNameTestDirectoryProvider testDir = new TestNameTestDirectoryProvider();
    DefaultFileWatchService fileWatchService
    File testDir
    long waitForEventsMillis = OperatingSystem.current().isMacOsX() ? 3100L : 1100L
    FileWatcher fileWatcher
    FileWatchInputs fileWatchInputs

    void setup() {
        NativeServicesTestFixture.initialize()
        fileWatchService = new DefaultFileWatchService()
        fileWatcher = fileWatchService.createFileWatcher()
        fileWatchInputs = new DefaultFileWatchInputs()
        fileWatchInputs.watch(new DirectoryFileTree(testDir.getTestDirectory()))
    }

    void cleanup() {
        fileWatcher.stop()
        fileWatchService.stop()
    }

    def "watch service should notify of new files"() {
        given:
        def fileWatchListener = Mock(FileWatchListener)
        when:
        fileWatcher.watch(fileWatchInputs, fileWatchListener)
        File createdFile = testDir.file("newfile.txt")
        createdFile.text = "Hello world"
        waitForChanges()
        then:
        1 * fileWatchListener.changesDetected(_)
    }

    private void waitForChanges() {
        sleep(waitForEventsMillis)
    }

    def "watch service should use default excludes"() {
        given:
        def fileWatchListener = Mock(FileWatchListener)
        when:
        fileWatcher.watch(fileWatchInputs, fileWatchListener)
        TestFile gitDir = testDir.createDir(".git")
        File createdFile = gitDir.file("some_git_object")
        createdFile.text = "some git data here, shouldn't trigger a change event"
        waitForChanges()
        then:
        0 * fileWatchListener.changesDetected(_)
    }

    def "watch service should notify of new files in subdirectories"() {
        given:
        def fileWatchListener = Mock(FileWatchListener)
        when:
        fileWatcher.watch(fileWatchInputs, fileWatchListener)
        def subdir = testDir.createDir("subdir")
        subdir.createFile("somefile").text = "Hello world"
        waitForChanges()
        then:
        1 * fileWatchListener.changesDetected(_)
        when:
        subdir.file('someotherfile').text = "Hello world"
        waitForChanges()
        then:
        1 * fileWatchListener.changesDetected(_)
    }

    def "default ignored files shouldn't trigger changes"() {
        given:
        def fileWatchListener = Mock(FileWatchListener)
        when:
        fileWatcher.watch(fileWatchInputs, fileWatchListener)
        testDir.file('some_temp_file~').text = "This change should be ignored"
        waitForChanges()
        then:
        0 * fileWatchListener.changesDetected(_)
    }
}
