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

import org.gradle.internal.filewatch.FileWatchListener
import org.gradle.internal.filewatch.FileWatcher
import spock.lang.Specification

import java.nio.file.Path
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by lari on 13/04/15.
 */
class FileWatcherExecutorTest extends Specification {
    def fileWatcher
    def runningFlag
    def fileWatchListener
    def directories
    def files
    def watchService
    def fileWatcherExecutor
    Map<File, Path> dirToPathMocks = [:]

    def setup() {
        fileWatcher = Mock(FileWatcher)
        runningFlag = new AtomicBoolean(true)
        fileWatchListener = Mock(FileWatchListener)
        directories = []
        files = []
        watchService = Mock(WatchService)
        fileWatcherExecutor = new FileWatcherExecutor(fileWatcher, runningFlag, fileWatchListener, directories, files) {
            @Override
            protected boolean supportsWatchingSubTree() {
                return false
            }

            @Override
            protected WatchService createWatchService() {
                return watchService
            }

            @Override
            protected Path dirToPath(File dir) {
                Path p = dirToPathMocks.get(dir)
                if(p == null) {
                    p = Mock(Path)
                    dirToPathMocks.put(dir, p)
                }
                p
            }
        }
    }

    def "test registering inputs"() {
        given:
        runningFlag.set(false)
        def file = new File("a/b/c")
        files << file
        def pathMock = Mock(Path)
        dirToPathMocks.put(file.getParentFile(), pathMock)
        def watchKey = Mock(WatchKey)
        when:
        fileWatcherExecutor.run()
        then:
        pathMock.register(watchService, FileWatcherExecutor.WATCH_KINDS, FileWatcherExecutor.WATCH_MODIFIERS) >> {
            watchKey
        }
    }
}
